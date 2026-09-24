//! JNI wrapper that embeds Arti (the Tor Project's Rust Tor client) into LibreNostr.
//!
//! The library exposes one Tor client per process as a **loopback SOCKS5 proxy**. Kotlin starts the
//! client with [`initialize`], binds the listener with [`start_socks`] and then simply points its
//! HTTP clients at `127.0.0.1:<port>`; everything else (circuits, guards, directory) stays in Rust.
//!
//! The design follows lessons taken from a read of Amethyst's equivalent wrapper (MIT), rebuilt here
//! rather than copied, because our JNI package differs and several things are done differently:
//!
//! * **`OnDemand` bootstrap.** The client exists within milliseconds and the SOCKS port binds
//!   immediately; the directory download (seconds warm, tens of seconds cold) runs in the background
//!   and every stream waits for its own circuit. Blocking `initialize` on the whole download made the
//!   caller's lifecycle lock unusable for that long and stranded fresh installs.
//! * **Readiness and progress are polled**, never inferred from log text.
//! * **One private tokio runtime per engine.** Dropping the runtime on [`destroy`] cancels every task
//!   at once — the SOCKS handlers and Arti's own background tasks — which is what releases Arti's
//!   state-file lock so a later `initialize` can take it again. No per-connection task bookkeeping.
//! * **SOCKS5 with exact reads**, so a request split across TCP segments (legal, and what a slow
//!   client produces) is handled instead of rejected; a handshake timeout and a cap on concurrent
//!   streams keep a misbehaving local client from pinning resources.
//! * **Ephemeral port**: `start_socks(0)` returns the port the OS picked, so nothing has to guess a
//!   free one and there is no well-known port for another app to probe.
//! * **Logs are queued in Rust and drained by Kotlin** (`nativePollLog`) instead of calling back into
//!   the JVM from arbitrary threads, which removes the JavaVM attach / global reference machinery.
//! * **Connect outcomes are counted** so the caller can detect "Tor looks active but nothing
//!   connects" from `ErrorKind::TorAccessFailed` streaks, not from text matching.

use std::collections::VecDeque;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, Once};
use std::time::Duration;

use arti_client::config::TorClientConfigBuilder;
use arti_client::{BootstrapBehavior, DormantMode, ErrorKind, HasKind, TorClient};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::runtime::Runtime;
use tokio::sync::Semaphore;
use tokio::task::JoinHandle;
use tor_rtcompat::PreferredRuntime;

type Client = Arc<TorClient<PreferredRuntime>>;

/// Result codes shared with `ArtiNative.kt`. Negative means failure.
const OK: i32 = 0;
const ERR_BAD_ARGUMENT: i32 = -1;
const ERR_NO_ENGINE: i32 = -2;
const ERR_RUNTIME: i32 = -3;
const ERR_CLIENT: i32 = -4;
const ERR_BIND: i32 = -5;

/// How long a local client has to finish the SOCKS handshake before the connection is dropped.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
/// An upper bound on establishing one Tor stream. Arti has its own, tighter timeouts; this only
/// stops a stuck attempt from holding a handler forever.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(120);
/// Concurrent proxied streams. A feed opens dozens of relay and media connections at once; this is
/// generous for that and still bounded.
const MAX_CONCURRENT_STREAMS: usize = 512;
/// Tokio worker threads. Two are plenty for a SOCKS proxy and keep CPU use modest on a phone; the
/// default would be one per core.
const WORKER_THREADS: usize = 2;
/// Queued log lines kept for Kotlin to drain; the oldest are dropped past this.
const LOG_CAPACITY: usize = 512;
/// How long `destroy` waits for tasks and blocking threads to stop.
const SHUTDOWN_GRACE: Duration = Duration::from_secs(3);

// ------------------------------------------------------------------------------------------------
// Global state
// ------------------------------------------------------------------------------------------------

struct Engine {
    runtime: Runtime,
    client: Client,
    socks: Option<JoinHandle<()>>,
    bootstrap: Option<JoinHandle<()>>,
}

static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);
static TLS_PROVIDER: Once = Once::new();
static LOG: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());

/// Connect outcome counters, read by `nativeStat`.
struct Stats {
    connect_ok: AtomicU64,
    connect_failed: AtomicU64,
    /// Failures that mean "could not reach the Tor network at all" (`ErrorKind::TorAccessFailed`,
    /// which covers Arti's `AllGuardsDown` / `AllFallbacksDown`), since the last success.
    access_failed_streak: AtomicU64,
}

static STATS: Stats = Stats {
    connect_ok: AtomicU64::new(0),
    connect_failed: AtomicU64::new(0),
    access_failed_streak: AtomicU64::new(0),
};

const STAT_CONNECT_OK: i32 = 0;
const STAT_CONNECT_FAILED: i32 = 1;
const STAT_ACCESS_FAILED_STREAK: i32 = 2;

/// A poisoned mutex can only happen in test builds (release aborts on panic); keep working with the
/// data instead of cascading the panic into every later call.
fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn log(level: &str, message: impl AsRef<str>) {
    let mut queue = lock(&LOG);
    if queue.len() >= LOG_CAPACITY {
        queue.pop_front();
    }
    queue.push_back(format!("{level} {}", message.as_ref()));
}

// ------------------------------------------------------------------------------------------------
// Engine lifecycle (plain Rust; the JNI functions below are thin adapters over these)
// ------------------------------------------------------------------------------------------------

/// Creates the Tor client (once) and starts downloading the directory in the background.
///
/// Returns as soon as the client exists. Safe to call again while an engine is running: it is a
/// no-op, so a caller that lost track of the state cannot create a second client against the same
/// state directory.
fn initialize(data_dir: &str) -> i32 {
    if lock(&ENGINE).is_some() {
        log("INFO", "engine already initialized, reusing it");
        return OK;
    }

    TLS_PROVIDER.call_once(|| {
        // Returns Err when a provider is already installed, which is fine: we only need one.
        let _ = rustls::crypto::ring::default_provider().install_default();
    });

    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(WORKER_THREADS)
        .thread_name("nostr-arti")
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(error) => {
            log("ERROR", format!("could not create the async runtime: {error}"));
            return ERR_RUNTIME;
        }
    };

    let data = PathBuf::from(data_dir);
    let state_dir = data.join("state");
    let cache_dir = data.join("cache");
    let _ = std::fs::create_dir_all(&state_dir);
    let _ = std::fs::create_dir_all(&cache_dir);

    log("INFO", format!("creating the Tor client in {data_dir}"));
    let created = runtime.block_on(async {
        // `mut` is only needed by the host-only block below, which is compiled out on Android.
        #[cfg_attr(target_os = "android", allow(unused_mut))]
        let mut builder = TorClientConfigBuilder::from_directories(state_dir, cache_dir);

        // Arti's fs-mistrust walks every parent of the state directory and rejects one with an
        // unexpected owner. On Android the app's private files directory is sandboxed by the OS, so
        // the strict default is right. Host builds (the integration tests) live under /tmp on
        // machines with all sorts of ownership, so only there is the check relaxed.
        #[cfg(not(target_os = "android"))]
        {
            builder.storage().permissions().dangerously_trust_everyone();
        }

        let config = builder.build().map_err(|error| format!("invalid Tor config: {error}"))?;

        // OnDemand + the `_async` constructor: the client is usable at once, and the async variant
        // waits a short grace period for the state-file lock, which a destroy()/initialize() cycle
        // needs (the synchronous one does not wait at all).
        TorClient::builder()
            .config(config)
            .bootstrap_behavior(BootstrapBehavior::OnDemand)
            .create_unbootstrapped_async()
            .await
            .map_err(|error| format!("could not create the Tor client: {error}"))
    });

    let client: Client = match created {
        Ok(client) => client,
        Err(message) => {
            log("ERROR", message);
            // The runtime is dropped here, which cancels anything the failed attempt left running.
            runtime.shutdown_timeout(SHUTDOWN_GRACE);
            return ERR_CLIENT;
        }
    };

    // Start the directory download now instead of leaving it to the first stream. A failure here is
    // not fatal and deliberately not latched: with OnDemand the next stream retries on its own, and
    // readiness is read live from Arti, so a later recovery is picked up without any bookkeeping.
    let background = Arc::clone(&client);
    let bootstrap = runtime.spawn(async move {
        let started = std::time::Instant::now();
        match background.bootstrap().await {
            Ok(()) => log("INFO", format!("directory ready after {} ms", started.elapsed().as_millis())),
            Err(error) => log(
                "WARN",
                format!("directory download failed after {} ms (streams will retry): {error}", started.elapsed().as_millis()),
            ),
        }
    });

    STATS.access_failed_streak.store(0, Ordering::Relaxed);
    *lock(&ENGINE) = Some(Engine { runtime, client, socks: None, bootstrap: Some(bootstrap) });
    log("INFO", "Tor client created (directory downloading in the background)");
    OK
}

/// Binds the loopback SOCKS5 listener and returns the port it is bound to (`port` may be 0 to let
/// the OS choose). Replaces a listener that is already running.
fn start_socks(port: i32) -> i32 {
    if !(0..=65_535).contains(&port) {
        return ERR_BAD_ARGUMENT;
    }
    let mut guard = lock(&ENGINE);
    let Some(engine) = guard.as_mut() else {
        log("ERROR", "start_socks called without an engine");
        return ERR_NO_ENGINE;
    };
    if let Some(previous) = engine.socks.take() {
        previous.abort();
    }

    let listener = match std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, port as u16)) {
        Ok(listener) => listener,
        Err(error) => {
            log("ERROR", format!("could not bind 127.0.0.1:{port}: {error}"));
            return ERR_BIND;
        }
    };
    if listener.set_nonblocking(true).is_err() {
        return ERR_BIND;
    }
    let bound = match listener.local_addr() {
        Ok(address) => address.port(),
        Err(_) => return ERR_BIND,
    };

    // `TcpListener::from_std` needs a runtime context; `enter` provides it without blocking.
    let _context = engine.runtime.enter();
    let listener = match TcpListener::from_std(listener) {
        Ok(listener) => listener,
        Err(error) => {
            log("ERROR", format!("could not register the listener: {error}"));
            return ERR_BIND;
        }
    };

    let client = Arc::clone(&engine.client);
    let permits = Arc::new(Semaphore::new(MAX_CONCURRENT_STREAMS));
    engine.socks = Some(engine.runtime.spawn(accept_loop(listener, client, permits)));
    log("INFO", format!("SOCKS5 proxy listening on 127.0.0.1:{bound}"));
    i32::from(bound)
}

/// Stops the listener. The Tor client stays alive, so stopping and starting the proxy never touches
/// the state-file lock.
fn stop_socks() -> i32 {
    if let Some(engine) = lock(&ENGINE).as_mut() {
        if let Some(listener) = engine.socks.take() {
            listener.abort();
            log("INFO", "SOCKS5 proxy stopped");
        }
    }
    OK
}

/// 1 when Tor can carry traffic now, 0 when not yet, -1 when there is no client.
///
/// Asks Arti live instead of remembering how the initial download ended: that download can fail
/// while the client stays perfectly usable (the next stream retries it), so a latched failure would
/// report a dead Tor forever against one that recovered.
fn is_bootstrapped() -> i32 {
    match lock(&ENGINE).as_ref() {
        Some(engine) => i32::from(engine.client.bootstrap_status().ready_for_traffic()),
        None => -1,
    }
}

/// Directory download progress in permille (0..=1000), or -1 without a client. Forward progress is
/// what separates a slow download from a stalled one; a fixed timeout cannot, because cold downloads
/// have been measured anywhere between 12 and 34 seconds on the same device.
fn bootstrap_progress() -> i32 {
    match lock(&ENGINE).as_ref() {
        Some(engine) => (engine.client.bootstrap_status().as_frac() * 1000.0).clamp(0.0, 1000.0) as i32,
        None => -1,
    }
}

/// `soft` suspends Arti's background tasks (saving CPU and battery while the app is in the
/// background); the next use of the client wakes it again.
fn set_dormant(soft: bool) {
    if let Some(engine) = lock(&ENGINE).as_ref() {
        engine.client.set_dormant(if soft { DormantMode::Soft } else { DormantMode::Normal });
    }
}

/// Tears everything down so a later `initialize` can start from scratch, including on the same
/// state directory.
///
/// Every task holds a clone of the client (`Arc`), and Arti's state-file lock is released only when
/// the last one is gone. Shutting the private runtime down cancels all of them at once.
fn destroy() -> i32 {
    let engine = lock(&ENGINE).take();
    let Some(mut engine) = engine else { return OK };

    if let Some(listener) = engine.socks.take() {
        listener.abort();
    }
    if let Some(bootstrap) = engine.bootstrap.take() {
        bootstrap.abort();
    }
    let Engine { runtime, client, .. } = engine;
    runtime.shutdown_timeout(SHUTDOWN_GRACE);
    drop(client);

    STATS.access_failed_streak.store(0, Ordering::Relaxed);
    log("INFO", "Tor client destroyed");
    OK
}

fn stat(index: i32) -> i64 {
    let counter = match index {
        STAT_CONNECT_OK => &STATS.connect_ok,
        STAT_CONNECT_FAILED => &STATS.connect_failed,
        STAT_ACCESS_FAILED_STREAK => &STATS.access_failed_streak,
        _ => return -1,
    };
    counter.load(Ordering::Relaxed) as i64
}

/// Drains the queued log lines as one newline-separated string, or `None` when there are none.
fn poll_log() -> Option<String> {
    let mut queue = lock(&LOG);
    if queue.is_empty() {
        return None;
    }
    let lines: Vec<String> = queue.drain(..).collect();
    Some(lines.join("\n"))
}

// ------------------------------------------------------------------------------------------------
// SOCKS5
// ------------------------------------------------------------------------------------------------

/// What a local client asked to reach.
#[derive(Debug, PartialEq, Eq)]
struct Target {
    host: String,
    port: u16,
}

/// Why a SOCKS exchange ended before a Tor stream was requested.
#[derive(Debug)]
enum SocksError {
    Io(std::io::Error),
    Protocol(&'static str),
}

impl std::fmt::Display for SocksError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SocksError::Io(error) => write!(formatter, "i/o error: {error}"),
            SocksError::Protocol(reason) => write!(formatter, "protocol error: {reason}"),
        }
    }
}

impl From<std::io::Error> for SocksError {
    fn from(error: std::io::Error) -> Self {
        SocksError::Io(error)
    }
}

/// A SOCKS5 reply with the given code and an all-zero bound address.
fn reply(code: u8) -> [u8; 10] {
    [0x05, code, 0x00, 0x01, 0, 0, 0, 0, 0, 0]
}

/// Reads a SOCKS5 greeting and a CONNECT request from `stream`.
///
/// Every field is read with `read_exact` by the length the protocol gives, so a request that arrives
/// in several TCP segments is assembled instead of being mistaken for a truncated one. The handshake
/// answers the client itself for the protocol errors it can name (no acceptable method, unsupported
/// command or address type); the caller answers for connect outcomes.
async fn negotiate<S>(stream: &mut S) -> Result<Target, SocksError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Err(SocksError::Protocol("not a SOCKS5 client"));
    }
    let mut methods = vec![0u8; usize::from(greeting[1])];
    stream.read_exact(&mut methods).await?;
    if !methods.contains(&0x00) {
        // 0xFF: "no acceptable methods". Only "no authentication" is offered; the listener is
        // bound to loopback and carries no secrets of its own.
        stream.write_all(&[0x05, 0xFF]).await?;
        return Err(SocksError::Protocol("client offered no supported authentication method"));
    }
    stream.write_all(&[0x05, 0x00]).await?;

    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    if header[0] != 0x05 {
        return Err(SocksError::Protocol("bad request version"));
    }
    if header[1] != 0x01 {
        stream.write_all(&reply(0x07)).await?; // command not supported
        return Err(SocksError::Protocol("only CONNECT is supported"));
    }

    let host = match header[3] {
        0x01 => {
            let mut octets = [0u8; 4];
            stream.read_exact(&mut octets).await?;
            Ipv4Addr::from(octets).to_string()
        }
        0x03 => {
            let mut length = [0u8; 1];
            stream.read_exact(&mut length).await?;
            let mut name = vec![0u8; usize::from(length[0])];
            stream.read_exact(&mut name).await?;
            match String::from_utf8(name) {
                Ok(name) if !name.is_empty() => name,
                _ => {
                    stream.write_all(&reply(0x01)).await?;
                    return Err(SocksError::Protocol("invalid domain name"));
                }
            }
        }
        0x04 => {
            let mut octets = [0u8; 16];
            stream.read_exact(&mut octets).await?;
            Ipv6Addr::from(octets).to_string()
        }
        _ => {
            stream.write_all(&reply(0x08)).await?; // address type not supported
            return Err(SocksError::Protocol("unsupported address type"));
        }
    };

    let mut port = [0u8; 2];
    stream.read_exact(&mut port).await?;
    Ok(Target { host, port: u16::from_be_bytes(port) })
}

/// The SOCKS5 reply code that means the same as an Arti connect failure.
///
/// Answering "connection refused" for everything collapsed the whole failure taxonomy into one
/// opaque string on the client side (Java renders 0x05 as `SocksException: Connection refused`),
/// which left callers with only their most generic retry policy. These are the codes Java surfaces
/// with distinct messages, so a client can tell "this host is gone" from "this circuit had a bad
/// minute".
fn reply_code_for(kind: ErrorKind) -> u8 {
    match kind {
        ErrorKind::RemoteHostNotFound | ErrorKind::RemoteHostResolutionFailed => 0x04, // host unreachable
        ErrorKind::RemoteConnectionRefused => 0x05,                                    // refused
        ErrorKind::ExitPolicyRejected => 0x02,                                         // not allowed
        ErrorKind::RemoteNetworkFailed => 0x03,                                        // network unreachable
        ErrorKind::ExitTimeout | ErrorKind::RemoteNetworkTimeout => 0x06,              // TTL expired
        _ => 0x01,                                                                     // general failure
    }
}

async fn accept_loop(listener: TcpListener, client: Client, permits: Arc<Semaphore>) {
    loop {
        let stream = match listener.accept().await {
            Ok((stream, _peer)) => stream,
            Err(error) => {
                // Usually descriptor exhaustion. Ending the loop would leave a proxy that is
                // "started" but never answers, so back off briefly and keep accepting.
                log("WARN", format!("accept failed: {error}"));
                tokio::time::sleep(Duration::from_millis(100)).await;
                continue;
            }
        };
        let Ok(permit) = Arc::clone(&permits).try_acquire_owned() else {
            log("WARN", "too many concurrent streams, dropping a connection");
            continue;
        };
        let client = Arc::clone(&client);
        tokio::spawn(async move {
            let _permit = permit;
            if let Err(error) = handle_connection(stream, client).await {
                log("DEBUG", format!("connection ended: {error}"));
            }
        });
    }
}

async fn handle_connection(mut stream: TcpStream, client: Client) -> Result<(), SocksError> {
    let target = match tokio::time::timeout(HANDSHAKE_TIMEOUT, negotiate(&mut stream)).await {
        Ok(result) => result?,
        Err(_) => return Err(SocksError::Protocol("handshake timed out")),
    };

    let outcome =
        tokio::time::timeout(CONNECT_TIMEOUT, client.connect((target.host.as_str(), target.port))).await;
    let mut tor_stream = match outcome {
        Ok(Ok(tor_stream)) => {
            STATS.connect_ok.fetch_add(1, Ordering::Relaxed);
            STATS.access_failed_streak.store(0, Ordering::Relaxed);
            tor_stream
        }
        Ok(Err(error)) => {
            let kind = error.kind();
            STATS.connect_failed.fetch_add(1, Ordering::Relaxed);
            if kind == ErrorKind::TorAccessFailed {
                STATS.access_failed_streak.fetch_add(1, Ordering::Relaxed);
            }
            log("DEBUG", format!("connect to {}:{} failed: {kind:?}", target.host, target.port));
            stream.write_all(&reply(reply_code_for(kind))).await?;
            return Ok(());
        }
        Err(_) => {
            STATS.connect_failed.fetch_add(1, Ordering::Relaxed);
            stream.write_all(&reply(0x06)).await?;
            return Ok(());
        }
    };

    stream.write_all(&reply(0x00)).await?;
    // Unlike racing the two directions, `copy_bidirectional` shuts each write half down when its
    // source ends, so a response still in flight after the client closed its side is not cut off.
    let _ = tokio::io::copy_bidirectional(&mut stream, &mut tor_stream).await;
    Ok(())
}

// ------------------------------------------------------------------------------------------------
// JNI
//
// Package `net.primal.core.networking.tor.engine`, class `ArtiNative` (see ArtiNative.kt). All
// functions are `static` on the Kotlin side, so the second argument is the class.
// ------------------------------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_net_primal_core_networking_tor_engine_ArtiNative_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let version = format!("nostr-arti {} (arti-client 0.46)", env!("CARGO_PKG_VERSION"));
    env.new_string(version).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_net_primal_core_networking_tor_engine_ArtiNative_nativeInitialize(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
) -> jint {
    match env.get_string(&data_dir) {
        Ok(path) => initialize(&String::from(path)),
        Err(_) => ERR_BAD_ARGUMENT,
    }
}

#[no_mangle]
pub extern "system" fn Java_net_primal_core_networking_tor_engine_ArtiNative_nativeStartSocks(
    _env: JNIEnv,
    _class: JClass,
    port: jint,
) -> jint {
    start_socks(port)
}

#[no_mangle]
pub extern "system" fn Java_net_primal_core_networking_tor_engine_ArtiNative_nativeStopSocks(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    stop_socks()
}

#[no_mangle]
pub extern "system" fn Java_net_primal_core_networking_tor_engine_ArtiNative_nativeIsBootstrapped(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    is_bootstrapped()
}

#[no_mangle]
pub extern "system" fn Java_net_primal_core_networking_tor_engine_ArtiNative_nativeBootstrapProgress(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    bootstrap_progress()
}

#[no_mangle]
pub extern "system" fn Java_net_primal_core_networking_tor_engine_ArtiNative_nativeSetDormant(
    _env: JNIEnv,
    _class: JClass,
    soft: jboolean,
) {
    set_dormant(soft != 0);
}

#[no_mangle]
pub extern "system" fn Java_net_primal_core_networking_tor_engine_ArtiNative_nativeStat(
    _env: JNIEnv,
    _class: JClass,
    index: jint,
) -> jlong {
    stat(index)
}

#[no_mangle]
pub extern "system" fn Java_net_primal_core_networking_tor_engine_ArtiNative_nativePollLog(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match poll_log() {
        Some(lines) => env.new_string(lines).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_net_primal_core_networking_tor_engine_ArtiNative_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    destroy()
}

// ------------------------------------------------------------------------------------------------
// Tests: the protocol and mapping logic, which needs no network and no Tor.
// ------------------------------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::duplex;

    /// Runs `negotiate` against a client that writes `chunks` one at a time with a pause between
    /// them, so each lands in a separate read on the server side, and returns the result plus every
    /// byte the server wrote back.
    async fn negotiate_chunks(chunks: &[&[u8]]) -> (Result<Target, SocksError>, Vec<u8>) {
        let (mut client, mut server) = duplex(1024);
        let writer = async {
            for chunk in chunks {
                client.write_all(chunk).await.unwrap();
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
            // Close our write half, as a client that is done sending does. Without it a truncated
            // request would leave the server waiting for bytes that never come; with it the server
            // sees end-of-stream and `read_exact` fails. Reading the replies stays possible.
            client.shutdown().await.unwrap();
        };
        let (result, ()) = tokio::join!(negotiate(&mut server), writer);
        drop(server);
        let mut answered = Vec::new();
        client.read_to_end(&mut answered).await.unwrap();
        (result, answered)
    }

    #[tokio::test]
    async fn parses_a_domain_request() {
        let mut request = vec![0x05, 0x01, 0x00, 0x03, 11];
        request.extend_from_slice(b"example.com");
        request.extend_from_slice(&443u16.to_be_bytes());

        let (result, answered) = negotiate_chunks(&[&[0x05, 0x01, 0x00], &request]).await;

        assert_eq!(result.unwrap(), Target { host: "example.com".into(), port: 443 });
        assert_eq!(answered, vec![0x05, 0x00], "the greeting is accepted, nothing else is sent yet");
    }

    #[tokio::test]
    async fn parses_a_request_split_across_many_segments() {
        // The regression this guards against: a wrapper that does a single read and expects the whole
        // request in it rejects this perfectly legal stream.
        let mut request = vec![0x05, 0x01, 0x00, 0x03, 9];
        request.extend_from_slice(b"nostr.wtf");
        request.extend_from_slice(&7777u16.to_be_bytes());
        let pieces: Vec<&[u8]> = std::iter::once(&[0x05u8][..])
            .chain(std::iter::once(&[0x01u8, 0x00][..]))
            .chain(request.chunks(2))
            .collect();

        let (result, _) = negotiate_chunks(&pieces).await;

        assert_eq!(result.unwrap(), Target { host: "nostr.wtf".into(), port: 7777 });
    }

    #[tokio::test]
    async fn parses_ipv4_and_ipv6_requests() {
        let mut v4 = vec![0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1];
        v4.extend_from_slice(&80u16.to_be_bytes());
        let (result, _) = negotiate_chunks(&[&[0x05, 0x01, 0x00], &v4]).await;
        assert_eq!(result.unwrap(), Target { host: "127.0.0.1".into(), port: 80 });

        let mut v6 = vec![0x05, 0x01, 0x00, 0x04];
        v6.extend_from_slice(&Ipv6Addr::LOCALHOST.octets());
        v6.extend_from_slice(&8080u16.to_be_bytes());
        let (result, _) = negotiate_chunks(&[&[0x05, 0x01, 0x00], &v6]).await;
        assert_eq!(result.unwrap(), Target { host: "::1".into(), port: 8080 });
    }

    #[tokio::test]
    async fn refuses_a_client_that_offers_only_authentication() {
        let (result, answered) = negotiate_chunks(&[&[0x05, 0x01, 0x02]]).await;

        assert!(matches!(result, Err(SocksError::Protocol(_))));
        assert_eq!(answered, vec![0x05, 0xFF]);
    }

    #[tokio::test]
    async fn answers_command_not_supported_for_anything_but_connect() {
        // BIND (0x02) to a domain.
        let mut request = vec![0x05, 0x02, 0x00, 0x03, 1, b'a'];
        request.extend_from_slice(&1u16.to_be_bytes());

        let (result, answered) = negotiate_chunks(&[&[0x05, 0x01, 0x00], &request]).await;

        assert!(matches!(result, Err(SocksError::Protocol(_))));
        assert_eq!(&answered[2..4], &[0x05, 0x07]);
    }

    #[tokio::test]
    async fn answers_address_type_not_supported() {
        let (result, answered) =
            negotiate_chunks(&[&[0x05, 0x01, 0x00], &[0x05, 0x01, 0x00, 0x09]]).await;

        assert!(matches!(result, Err(SocksError::Protocol(_))));
        assert_eq!(&answered[2..4], &[0x05, 0x08]);
    }

    #[tokio::test]
    async fn a_truncated_request_is_an_io_error_not_a_hang() {
        // The client sends a partial request and closes.
        let (result, _) = negotiate_chunks(&[&[0x05, 0x01, 0x00], &[0x05, 0x01]]).await;

        assert!(matches!(result, Err(SocksError::Io(_))));
    }

    #[test]
    fn maps_arti_errors_to_distinct_socks_replies() {
        assert_eq!(reply_code_for(ErrorKind::RemoteHostNotFound), 0x04);
        assert_eq!(reply_code_for(ErrorKind::RemoteHostResolutionFailed), 0x04);
        assert_eq!(reply_code_for(ErrorKind::RemoteConnectionRefused), 0x05);
        assert_eq!(reply_code_for(ErrorKind::ExitPolicyRejected), 0x02);
        assert_eq!(reply_code_for(ErrorKind::RemoteNetworkFailed), 0x03);
        assert_eq!(reply_code_for(ErrorKind::ExitTimeout), 0x06);
        assert_eq!(reply_code_for(ErrorKind::TorAccessFailed), 0x01, "unclassified stays vague");
    }

    // --------------------------------------------------------------------------------------------
    // Engine tests. They drive the real Arti client, share the process-wide engine and (some) need
    // outbound access to the Tor network, so they are ignored by default. Run them with
    //
    //     cargo test --locked -- --ignored --test-threads=1 --nocapture
    //
    // (`run-host-tests.sh` does exactly that.)
    // --------------------------------------------------------------------------------------------

    use std::io::{Read, Write};
    use std::net::TcpStream as StdTcpStream;

    fn scratch_dir(name: &str) -> String {
        let dir = std::env::temp_dir().join(format!("nostr-arti-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir.to_string_lossy().into_owned()
    }

    fn connect_local(port: i32) -> StdTcpStream {
        let stream = StdTcpStream::connect((Ipv4Addr::LOCALHOST, port as u16)).unwrap();
        stream.set_read_timeout(Some(Duration::from_secs(60))).unwrap();
        stream
    }

    #[test]
    #[ignore = "engine test: run with --ignored --test-threads=1"]
    fn destroy_releases_the_state_lock_so_the_same_directory_can_be_reused() {
        // Arti's state-file lock is held until every clone of the client is gone. A destroy() that
        // leaves one behind makes the next initialize() on the same directory fail, which in the app
        // strands Tor after the first self-heal. Cycle several times to also catch slow leaks.
        let dir = scratch_dir("lock");
        for cycle in 0..4 {
            assert_eq!(initialize(&dir), OK, "initialize failed on cycle {cycle}");
            assert!(start_socks(0) > 0, "no proxy on cycle {cycle}");
            assert_eq!(destroy(), OK);
            assert_eq!(is_bootstrapped(), -1, "no client may remain after destroy");
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    #[ignore = "engine test: run with --ignored --test-threads=1"]
    fn stopping_and_restarting_the_proxy_keeps_the_client() {
        let dir = scratch_dir("restart");
        assert_eq!(initialize(&dir), OK);
        let first = start_socks(0);
        assert!(first > 0);
        assert_eq!(stop_socks(), OK);
        assert_ne!(is_bootstrapped(), -1, "stopping the proxy must not destroy the client");
        let second = start_socks(0);
        assert!(second > 0);
        assert_eq!(initialize(&dir), OK, "initialize is a no-op while an engine is running");
        destroy();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    #[ignore = "engine test: run with --ignored --test-threads=1"]
    fn a_greeting_delivered_one_byte_at_a_time_is_accepted() {
        // The regression a single-read SOCKS parser has: nothing here needs the Tor network.
        let dir = scratch_dir("fragment");
        assert_eq!(initialize(&dir), OK);
        let port = start_socks(0);
        assert!(port > 0);

        let mut stream = connect_local(port);
        for byte in [0x05u8, 0x01, 0x00] {
            stream.write_all(&[byte]).unwrap();
            stream.flush().unwrap();
            std::thread::sleep(Duration::from_millis(30));
        }
        let mut answer = [0u8; 2];
        stream.read_exact(&mut answer).unwrap();
        assert_eq!(answer, [0x05, 0x00]);

        drop(stream);
        destroy();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    #[ignore = "needs outbound access to the Tor network; run with --ignored --test-threads=1"]
    fn a_request_really_goes_through_tor() {
        let dir = scratch_dir("tor");
        assert_eq!(initialize(&dir), OK);
        let port = start_socks(0);
        assert!(port > 0);

        // Wait for the directory. A cold download has been seen to take 12-35 s.
        let started = std::time::Instant::now();
        let mut last_progress = -2;
        while is_bootstrapped() != 1 {
            let progress = bootstrap_progress();
            if progress != last_progress {
                println!("bootstrap {progress}/1000 after {:?}", started.elapsed());
                last_progress = progress;
            }
            assert!(started.elapsed() < Duration::from_secs(240), "Tor did not bootstrap in time");
            std::thread::sleep(Duration::from_millis(500));
        }
        println!("ready after {:?}", started.elapsed());

        // SOCKS5 CONNECT by domain name (so the name is resolved by the exit, not locally) to port
        // 443, then a TLS session and a plain HTTPS request to the Tor Project's checker, which says
        // whether we arrived over Tor. Port 80 would only get a redirect to HTTPS.
        let mut stream = connect_local(port);
        stream.write_all(&[0x05, 0x01, 0x00]).unwrap();
        let mut method = [0u8; 2];
        stream.read_exact(&mut method).unwrap();
        assert_eq!(method, [0x05, 0x00]);

        let host = "check.torproject.org";
        let mut request = vec![0x05, 0x01, 0x00, 0x03, host.len() as u8];
        request.extend_from_slice(host.as_bytes());
        request.extend_from_slice(&443u16.to_be_bytes());
        stream.write_all(&request).unwrap();
        let mut answer = [0u8; 10];
        stream.read_exact(&mut answer).unwrap();
        assert_eq!(answer[1], 0x00, "SOCKS connect failed with code {:#04x}", answer[1]);

        let roots = rustls::RootCertStore { roots: webpki_roots::TLS_SERVER_ROOTS.to_vec() };
        let config = Arc::new(rustls::ClientConfig::builder().with_root_certificates(roots).with_no_client_auth());
        let name = rustls::pki_types::ServerName::try_from(host).unwrap();
        let mut connection = rustls::ClientConnection::new(config, name).unwrap();
        let mut tls = rustls::Stream::new(&mut connection, &mut stream);
        tls.write_all(b"GET /api/ip HTTP/1.1\r\nHost: check.torproject.org\r\nConnection: close\r\n\r\n")
            .unwrap();
        let mut body = Vec::new();
        // A server that closes without a TLS close_notify makes rustls report an error after the
        // data has arrived; what was read up to then is still the response.
        let _ = tls.read_to_end(&mut body);
        let body = String::from_utf8_lossy(&body).into_owned();
        println!("{body}");
        assert!(body.contains("\"IsTor\":true"), "the request did not arrive through Tor: {body}");

        assert!(stat(STAT_CONNECT_OK) >= 1);
        assert_eq!(stat(STAT_ACCESS_FAILED_STREAK), 0);

        // The Kotlin side reads Arti's guard sample from this exact path (ArtiTorEngine.GUARDS_FILE_PATH)
        // to detect a wedged sample; if Arti ever moves the file the detector silently stops working,
        // so pin the location here. It is written shortly after the first circuit is built.
        let guards = std::path::Path::new(&dir).join("state/state/guards.json");
        let deadline = std::time::Instant::now() + Duration::from_secs(30);
        while !guards.exists() && std::time::Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(250));
        }
        assert!(guards.exists(), "guards.json not found at {}", guards.display());
        let text = std::fs::read_to_string(&guards).unwrap();
        assert!(text.contains("\"guards\""), "unexpected guards.json shape: {text}");
        // When does the sample record a *confirmed* guard? The supervisor uses that as durable proof
        // that Tor worked on this install, and Arti writes the file pretty-printed, so match with a
        // real JSON parse rather than a substring. Observed: the file is first written with an empty
        // sample and filled in a little later, so wait for it.
        let has_confirmed = |content: &str| {
            content.lines().any(|line| {
                let line = line.trim_start();
                line.starts_with("\"confirmed_at\":") && !line.contains("null")
            })
        };
        let waited = std::time::Instant::now();
        let mut latest = text;
        while !has_confirmed(&latest) && waited.elapsed() < Duration::from_secs(60) {
            std::thread::sleep(Duration::from_millis(500));
            latest = std::fs::read_to_string(&guards).unwrap_or_default();
        }
        println!("confirmed guard on disk after {:?} more", waited.elapsed());
        assert!(has_confirmed(&latest), "no confirmed guard persisted within 60 s: {latest}");
        if let Ok(path) = std::env::var("NOSTR_ARTI_DUMP_GUARDS") {
            let _ = std::fs::write(path, &latest);
        }

        destroy();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_log_queue_is_bounded_and_drains_in_order() {
        lock(&LOG).clear();
        for index in 0..(LOG_CAPACITY + 10) {
            log("INFO", format!("line {index}"));
        }

        let drained = poll_log().unwrap();
        let lines: Vec<&str> = drained.lines().collect();

        assert_eq!(lines.len(), LOG_CAPACITY);
        assert_eq!(lines[0], "INFO line 10", "the oldest lines were dropped");
        assert_eq!(poll_log(), None, "draining empties the queue");
    }
}
