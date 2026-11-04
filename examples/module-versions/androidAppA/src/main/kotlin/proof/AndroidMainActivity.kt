package proof

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import kotlinx.coroutines.runBlocking

class AndroidMainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = try {
            Log.i("PariKtProof", "Android activity before explicit init")
            // Keep this proof on one thread; the real demo uses its Compose coroutine scope.
            runBlocking { verifySharedAndroidEngine(BuildConfig.PARI_EXPECTED_VERSION) }
        } catch (failure: Throwable) {
            Log.e("PariKtProof", "Engine verification failed", failure)
            "FAIL: $failure"
        }
        Log.i("PariKtProof", result)
        setContentView(TextView(this).apply { text = result; textSize = 18f; setPadding(24, 48, 24, 24) })
    }
}
