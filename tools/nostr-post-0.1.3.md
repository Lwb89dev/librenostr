# LibreNostr 0.1.2 + 0.1.3 — recap post

## Versione lunga

LibreNostr 0.1.3 è fuori. Insieme alla 0.1.2 è il giro più grosso finora, quindi
un recap unico.

**Via Google, per davvero.** Niente più flavor Play: fuori Play Billing, ML Kit,
Cronet, Firebase, i plugin google-services e play-publishing. Ma la cosa
interessante è quella che è sopravvissuta alla prima passata: Google Play
Services entrava ancora, di nascosto, come dipendenza transitiva dell'SDK Breez
Spark via credentials-play-services-auth. E quel backend wallet era già disattivato
nel codice — restituiva un servizio disabilitato ignorando tutto — quindi
costava 17,8 MB di codice nativo per ABI e l'intero stack play-services auth/fido
per zero funzionalità. Rimosso. L'APK è passato da 78,6 a 55,2 MB.

**Via il Premium di Primal.** 138 file, 14.742 righe: tier Legend/OG, primal
names, leaderboard, rebroadcast, acquisti in-app. Nulla di tutto ciò può
funzionare senza i server Primal. Il grosso dell'intreccio era cosmetico — lo
styling "Legend" decorava ogni avatar e nome nell'app, 466 occorrenze su 111 file
— ma essendo alimentato da eventi proprietari che in relay-only non arrivano mai,
era sempre inerte.

**Privacy, tre interventi concreti:**

- Le foto vengono ripulite dai metadati prima di partire verso Blossom. Uno
  scatto da fotocamera porta con sé EXIF: coordinate GPS a pochi metri, ora
  esatta, marca/modello/seriale del dispositivo, spesso il nome del proprietario.
  Riscrittura a livello di byte per JPEG, PNG e WebP: i pixel sono copiati
  invariati, niente ricodifica, niente perdita di qualità.
- Le query dei messaggi diretti non vengono più trasmesse ai relay pubblici di
  fallback. Aprire la tab messaggi comunicava a sette operatori terzi la tua
  pubkey e il fatto che stessi leggendo i DM, indipendentemente da come avevi
  configurato i tuoi relay.
- I feed long-form erano agganciati al firehose globale quando la lista autori
  risultava vuota — e il firehose long-form pubblico è in larga parte spam. Ora
  ogni query è vincolata ai tuoi follow, allargata al massimo ai follow dei tuoi
  follow. Mai global.

**E i fix della 0.1.3:**

Le notifiche erano lente e incomplete. Un filtro Nostr accetta una lista di kind,
quindi basta una REQ: ne partivano cinque, ognuna verso due pool di relay. Peggio,
ogni kind riceveva il limite pieno per conto suo e poi l'unione veniva ritagliata,
quindi la pagina era il kind più prolifico e il resto spariva. Gli zap erano anche
attribuiti alla persona sbagliata: una ricevuta NIP-57 è firmata dal server LNURL
del destinatario, non da chi zappa.

Gli highlight NIP-84 non funzionavano per due ragioni indipendenti: venivano
scaricati solo se la fetch dell'articolo riusciva per prima, e con signer esterno
il kind 9802 non era nell'allowlist — l'evento veniva rifiutato in locale e Amber
non veniva nemmeno interpellato. La richiesta di permessi NIP-55 chiedeva la
pre-approvazione per il solo kind 1, quindi tutto il resto o chiedeva conferma a
ogni uso o falliva in silenzio. Ora le due liste sono una sola.

Codice, changelog e APK firmati: github.com/Lwb89dev/librenostr

#nostr #android #foss

---

## Versione corta

LibreNostr 0.1.3 è fuori.

Via Google per davvero: niente Play Services, Play Billing, ML Kit, Firebase.
Quello che era sfuggito alla prima passata entrava come dipendenza transitiva
dell'SDK Breez — 17,8 MB di nativo per un wallet già disattivato. APK da 78,6 a
55,2 MB.

Via il Premium di Primal: 138 file, 14.742 righe che senza i loro server non
potevano funzionare comunque.

Privacy: gli EXIF delle foto (GPS incluso) vengono rimossi prima dell'upload su
Blossom; le query dei DM non finiscono più sui relay pubblici di fallback; i feed
long-form vengono dai tuoi follow e al massimo dalla loro rete, non dal global.

Fix: notifiche più veloci e complete (una REQ invece di cinque), zap attribuiti a
chi zappa davvero e non al server LNURL, highlight NIP-84 finalmente funzionanti
con Amber.

github.com/Lwb89dev/librenostr

#nostr #android #foss
