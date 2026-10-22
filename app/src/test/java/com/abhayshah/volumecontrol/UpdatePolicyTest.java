package com.abhayshah.volumecontrol;
import org.junit.Test;

public class UpdatePolicyTest {
    private static final String URL = "https://github.com/AbhayShah06/VolumeControl/releases/download/v0.4.0/VolumeControl.apk";
    private static final String HASH = "a".repeat(64);
    @Test public void acceptsNewerTrustedRelease() { UpdatePolicy.validate(5, 4, URL, HASH); }
    @Test(expected = IllegalArgumentException.class) public void rejectsPlaceholderHash() { UpdatePolicy.validate(5, 4, URL, "PENDING"); }
    @Test(expected = IllegalArgumentException.class) public void rejectsDowngrade() { UpdatePolicy.validate(4, 5, URL, HASH); }
    @Test(expected = IllegalArgumentException.class) public void rejectsUntrustedHost() { UpdatePolicy.validate(5, 4, URL.replace("github.com", "github.com.evil.test"), HASH); }
    @Test(expected = IllegalArgumentException.class) public void rejectsOtherRepository() { UpdatePolicy.validate(5, 4, URL.replace("VolumeControl/releases", "Other/releases"), HASH); }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnencryptedDownload() { UpdatePolicy.validate(5, 4, URL.replace("https:", "http:"), HASH); }
}
