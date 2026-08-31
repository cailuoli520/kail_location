package com.kail.location.inject.fakelocation.service;

import com.kail.location.inject.utils.LicenseStateManager;
import java.util.List;
import com.kail.location.inject.fakelocation.aidl.IHideRootManager;

/**
 * 隐藏 Root 服务的 system_server 侧实现（以 oem_integrity 名注册）。
 *
 * 维护两类全局配置供 hook 侧读取：
 *  - hideRootEnabled：是否启用整个隐藏 Root 功能（默认由 License 决定）；
 *  - 隐藏包列表 / 隐藏进程列表：需要对检测方隐藏的包名与进程名。
 * 运行时通过 binder 接收宿主 Kail 应用的更新指令，配置在静态字段中，
 * 因此 zygote 中挂载的 hook（如各种 integrity 检测 hook）可以随时读到最新值。
 */
public class HideRootManagerService extends IHideRootManager.Stub {

    /** 隐藏 Root 总开关（静态，供 hook 全局读取）。 */
    private static boolean hideRootEnabled;

    /** 需要隐藏的包名列表（静态，供 hook 全局读取）。 */
    private static List<String> hiddenPackages;

    /** 需要隐藏的进程名列表（静态，供 hook 全局读取）。 */
    private static List<String> hiddenProcesses;

    /** 配置读写的互斥锁，避免 binder 线程与 hook 线程并发读写列表。 */
    private Object configLock = new Object();

    /** "隐藏应用列表"功能的独立开关（与总开关区分，不影响 hideRootEnabled）。 */
    private boolean hideAppListEnabled;

    /**
     * 获取需要被隐藏的进程名列表。
     *
     * @return 隐藏进程列表；未设置时可能为 null
     */
    @Override
    public List<String> getHiddenProcesses() {
        List<String> processes;
        synchronized (this.configLock) {
            processes = hiddenProcesses;
        }
        return processes;
    }

    /**
     * 更新授权信息（License 序号 + 设备号），用于刷新隐藏 Root 功能是否可用。
     *
     * @param licenseToken 授权令牌
     * @param deviceId     当前设备 ID
     */
    @Override
    public void updateLicenseState(String licenseToken, String deviceId) {
        LicenseStateManager.updateLicenseState(licenseToken, deviceId);
    }

    /**
     * 设置需要被隐藏的进程名列表。
     *
     * @param processes 隐藏进程列表
     */
    @Override
    public void setHiddenProcesses(List<String> processes) {
        synchronized (this.configLock) {
            hiddenProcesses = processes;
        }
    }

    /**
     * 更新"隐藏应用列表"功能开关。
     *
     * @param enabled true 启用，false 关闭
     */
    @Override
    public void setHideAppListEnabled(boolean enabled) {
        this.hideAppListEnabled = enabled;
    }

    /**
     * 查询"隐藏应用列表"功能是否启用。
     *
     * @return true 启用，false 关闭
     */
    @Override
    public boolean isHideAppListEnabled() {
        return this.hideAppListEnabled;
    }

    /**
     * 设置需要被隐藏的包名列表（检测方查询包时会滤除这些包）。
     *
     * @param packages 隐藏包列表
     */
    @Override
    public void setHiddenPackages(List<String> packages) {
        synchronized (this.configLock) {
            hiddenPackages = packages;
        }
    }

    /**
     * 获取需要被隐藏的包名列表。
     *
     * @return 隐藏包列表；未设置时可能为 null
     */
    @Override
    public List<String> getHiddenPackages() {
        List<String> packages;
        synchronized (this.configLock) {
            packages = hiddenPackages;
        }
        return packages;
    }

    /**
     * 直接关闭隐藏 Root 功能（例如调试时临时关闭）。
     */
    @Override
    public void disableHideRoot() {
        hideRootEnabled = false;
    }

    /**
     * 按当前 License 可用性刷新隐藏 Root 总开关
     * （License 恢复可用则重新启用）。
     */
    @Override
    public void refreshHideRootEnabled() {
        hideRootEnabled = LicenseStateManager.isLicenseUsable();
    }

    /**
     * 查询隐藏 Root 功能是否启用。
     *
     * @return true 启用，false 关闭
     */
    @Override
    public boolean isHideRootEnabled() {
        return hideRootEnabled;
    }
}
