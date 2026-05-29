package mobile;

public class Mobile {
    public static native String generateConfigJSON();
    public static native String getVersion();
    public static native ConfigSummary summaryForConfig(String config);

    static {
        System.loadLibrary("gojni");
    }
}
