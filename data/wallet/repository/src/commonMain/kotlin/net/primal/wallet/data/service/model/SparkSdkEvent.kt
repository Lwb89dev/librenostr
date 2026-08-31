package net.primal.wallet.data.service.model

/**
 * Retained so [net.primal.wallet.data.service.SparkSdkEventProvider] keeps a type to
 * publish. Nothing emits these any more: the Breez Spark SDK that produced them is no
 * longer bundled. See [net.primal.wallet.data.repository.DisabledSparkWalletManager].
 */
internal object SparkSdkEvent
