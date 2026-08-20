package com.kail.location.inject.utils;

import android.os.RemoteException;
import java.util.List;
import com.kail.location.inject.fakelocation.aidl.IHideRootManager;

public class HideRootServiceManager {
    private IHideRootManager hideRootService;

    private static final class Holder {
        static HideRootServiceManager instance = new HideRootServiceManager();
    }

    public static HideRootServiceManager getInstance() {
        return Holder.instance;
    }

    public List<String> getHiddenPackages() {
        IHideRootManager svc = getHideRootService();
        if (svc != null) {
            try {
                return svc.getHiddenPackages();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        // binder 不可用（Enforcing 下 find 被 SELinux 拦截）时退到文件通道。
        return HideConfigFile.getPackages();
    }

    public List<String> getHiddenProcesses() {
        if (getHideRootService() == null) {
            return null;
        }
        try {
            return this.hideRootService.getHiddenProcesses();
        } catch (RemoteException e) {
            e.printStackTrace();
            return null;
        }
    }

    public IHideRootManager getHideRootService() {
        if (this.hideRootService == null) {
            try {
                this.hideRootService = IHideRootManager.Stub.asInterface(ServiceManagerBridge.getService(ClassLoader.getSystemClassLoader(), "oem_integrity"));
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        return this.hideRootService;
    }

    public boolean isHideRootEnabled() {
        IHideRootManager svc = getHideRootService();
        if (svc != null) {
            try {
                return svc.isHideRootEnabled();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return HideConfigFile.isEnabled();
    }

    public boolean isHideAppListEnabled() {
        IHideRootManager svc = getHideRootService();
        if (svc != null) {
            try {
                return svc.isHideAppListEnabled();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return HideConfigFile.isHideAppListEnabled();
    }

    public void disableHideRoot() {
        if (getHideRootService() == null) {
            return;
        }
        try {
            this.hideRootService.disableHideRoot();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}
