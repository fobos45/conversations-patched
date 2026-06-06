package eu.siacs.conversations.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import eu.siacs.conversations.utils.SignupUtils;

public class ConversationActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent originalIntent = getIntent();
        final Intent redirectIntent = SignupUtils.getRedirectionIntent(this);
        // если шарим файл — перенаправить в ShareWithActivity напрямую
        if (originalIntent != null
                && (Intent.ACTION_SEND.equals(originalIntent.getAction())
                        || Intent.ACTION_SEND_MULTIPLE.equals(originalIntent.getAction()))) {
            final Intent shareIntent = new Intent(this, ShareWithActivity.class);
            shareIntent.setAction(originalIntent.getAction());
            shareIntent.setType(originalIntent.getType());
            if (originalIntent.getExtras() != null) {
                shareIntent.putExtras(originalIntent.getExtras());
            }
            startActivity(shareIntent);
        } else {
            startActivity(redirectIntent);
        }
        new Handler(Looper.getMainLooper()).post(this::finish);
    }
}
