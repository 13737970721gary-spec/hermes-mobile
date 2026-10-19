package com.m57.hermescontrol.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.m57.hermescontrol.MainActivity

/**
 * Invisible trampoline for ACTION_SEND / ACTION_SEND_MULTIPLE (#1601). The share sheet may start
 * a target in the sender's task; forwarding with CLEAR_TOP|SINGLE_TOP lands in the one existing
 * [MainActivity] instead of stacking a second copy. The read grant is passed along with the intent.
 */
class ShareTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = intent
        if (savedInstanceState == null &&
            (incoming?.action == Intent.ACTION_SEND || incoming?.action == Intent.ACTION_SEND_MULTIPLE)
        ) {
            startActivity(
                Intent(incoming).apply {
                    setClass(this@ShareTargetActivity, MainActivity::class.java)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                },
            )
        }
        finish()
    }
}
