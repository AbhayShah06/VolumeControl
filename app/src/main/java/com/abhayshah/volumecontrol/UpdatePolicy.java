package com.abhayshah.volumecontrol;

import java.net.URI;

final class UpdatePolicy {
    private UpdatePolicy() { }
    static void validate(int offered, int installed, String url, String hash) {
        URI uri = URI.create(url);
        if (offered <= installed || !"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                || uri.getUserInfo() != null || uri.getPort() != -1
                || !uri.getPath().startsWith("/AbhayShah06/VolumeControl/releases/download/")
                || !uri.getPath().endsWith(".apk") || uri.getQuery() != null || uri.getFragment() != null
                || hash == null || !hash.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("Invalid update metadata");
        }
    }
}
