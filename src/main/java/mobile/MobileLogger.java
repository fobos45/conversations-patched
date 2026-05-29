package mobile;

public class MobileLogger {
    public void write(byte[] p) {
        android.util.Log.d("YggdrasilLib", new String(p));
    }
}
