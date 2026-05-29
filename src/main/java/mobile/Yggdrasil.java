package mobile;

public class Yggdrasil {
    public static native Yggdrasil new_();
    public native String startJSON(int tunFd, String config, MobileLogger logger);
    public native String stop();
    public native String getAddressString();
    public native String getSubnetString();
    public native String getPublicKeyString();
    public native long getMTU();
    public native String getPeersJSON();
    public native String getPathsJSON();
    public native String getTreeJSON();
    public native String retryPeersNow();
    public native byte[] recv();
    public native String send(byte[] data);

    static {
        System.loadLibrary("gojni");
    }
}
