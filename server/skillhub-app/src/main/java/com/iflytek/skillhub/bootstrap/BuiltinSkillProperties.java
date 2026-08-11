package com.iflytek.skillhub.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "skillhub.builtin-skills")
public class BuiltinSkillProperties {

    private boolean enabled = true;
    private String devPackageBaseUrl;
    private String devPackageDir;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDevPackageBaseUrl() {
        return devPackageBaseUrl;
    }

    public void setDevPackageBaseUrl(String devPackageBaseUrl) {
        this.devPackageBaseUrl = devPackageBaseUrl;
    }

    public String getDevPackageDir() {
        return devPackageDir;
    }

    public void setDevPackageDir(String devPackageDir) {
        this.devPackageDir = devPackageDir;
    }
}
