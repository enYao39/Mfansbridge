package local.piliplus.push;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Real launcher component that forwards to the module dashboard. */
public final class LauncherEntryActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, ModuleStatusActivity.class));
        finish();
    }
}
