package com.flappedear.betterdriver.sharing

import androidx.core.content.FileProvider
import com.flappedear.betterdriver.R

/**
 * Hands prepared session bundles and track files to the share sheet (FBBD-23, FBBD-80). Its own
 * class, because a manifest cannot list the same provider class twice (the updater has the other).
 */
class TransferFileProvider : FileProvider(R.xml.transfer_paths)
