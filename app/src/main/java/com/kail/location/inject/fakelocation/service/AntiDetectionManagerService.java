package com.kail.location.inject.fakelocation.service;

import com.kail.location.inject.utils.LicenseStateManager;
import com.kail.location.inject.utils.PackageAntiDetectionConfig;
import java.util.List;
import com.kail.location.inject.fakelocation.aidl.IMockAntiDetectionManager;

/**
 * 反检测服务的 system_server 侧实现（以 oem_security 名注册）。
 *
 * 持久化的配置（目标包列表、检测包列表、过滤开关、hook 开关等）全部委托给
 * {@link PackageAntiDetectionConfig}，License 状态委托给 {@link LicenseStateManager}；
 * 本类只充当 binder 通道，把来自宿主 Kail 应用（或其他 root 客户端）的调用
 * 转写到这些静态配置上，供 PackageManagerServiceHook 等 hook 运行时读取。
 */
public class AntiDetectionManagerService extends IMockAntiDetectionManager.Stub {

    /**
     * 获取当前生效的定向包规则列表（适用于特定包的反检测配置）。
     *
     * @return 规则包列表；未设置时可能为 null 或空
     */
    @Override
    public List<String> getScopedPackageRules() {
        return PackageAntiDetectionConfig.getScopedPackageRules();
    }

    /**
     * 更新授权信息（License 序号 + 设备号），用于刷新反检测功能是否可用。
     *
     * @param licenseToken 授权令牌（手动校验后写入）
     * @param deviceId     当前设备 ID，用于绑定设备授权
     */
    @Override
    public void updateLicenseState(String licenseToken, String deviceId) {
        LicenseStateManager.updateLicenseState(licenseToken, deviceId);
    }

    /**
     * 设置定向包规则列表。
     *
     * @param scopedPackageRules 需要启用定向反检测规则的包名列表
     */
    @Override
    public void setScopedPackageRules(List<String> scopedPackageRules) {
        PackageAntiDetectionConfig.setScopedPackageRules(scopedPackageRules);
    }

    /**
     * 开关"隐藏应用"主过滤器（PackageManagerServiceHook 据此决定是否拦截查询）。
     *
     * @param enabled true 开启包过滤，false 关闭
     */
    @Override
    public void setPackageFilterEnabled(boolean enabled) {
        PackageAntiDetectionConfig.setPackageFilterEnabled(enabled);
    }

    /**
     * 获取需要被隐藏的目标包名列表。
     *
     * @return 目标包列表
     */
    @Override
    public List<String> getTargetPackages() {
        return PackageAntiDetectionConfig.getTargetPackages();
    }

    /**
     * 设置需要被隐藏的目标包名列表。
     *
     * @param targetPackages 目标包列表
     */
    @Override
    public void setTargetPackages(List<String> targetPackages) {
        PackageAntiDetectionConfig.setTargetPackages(targetPackages);
    }

    /**
     * 查询"隐藏应用"主过滤器当前是否启用。
     *
     * @return true 已启用，false 未启用
     */
    @Override
    public boolean isPackageFilterEnabled() {
        return PackageAntiDetectionConfig.isPackageFilterEnabled();
    }

    /**
     * 查询包可见性过滤（queryIntentActivities 等）当前是否启用。
     *
     * @return true 已启用，false 未启用
     */
    @Override
    public boolean isPackageVisibilityFilteringEnabled() {
        return PackageAntiDetectionConfig.isPackageVisibilityFilteringEnabled();
    }

    /**
     * 关闭 PackageManagerServiceHook（例如调试阶段直接卸载 hook）。
     */
    @Override
    public void disablePackageManagerHook() {
        PackageAntiDetectionConfig.setPackageManagerHookEnabled(false);
    }

    /**
     * 查询 PackageManagerServiceHook 是否启用。
     *
     * @return true 启用，false 停用
     */
    @Override
    public boolean isPackageManagerHookEnabled() {
        return PackageAntiDetectionConfig.isPackageManagerHookEnabled();
    }

    /**
     * 获取需要额外判定的"检测已知包"列表（防检测列表）。
     *
     * @return 检测包列表
     */
    @Override
    public List<String> getDetectedPackages() {
        return PackageAntiDetectionConfig.getDetectedPackages();
    }

    /**
     * 按照当前 License 可用性刷新 PackageManagerServiceHook 的开关
     * （License 恢复可用则重新启用）。
     */
    @Override
    public void refreshPackageManagerHookEnabled() {
        PackageAntiDetectionConfig.setPackageManagerHookEnabled(LicenseStateManager.isLicenseUsable());
    }

    /**
     * 开关包可见性过滤（queryIntentActivities 等查询会隐藏目标包）。
     *
     * @param enabled true 开启可见性过滤，false 关闭
     */
    @Override
    public void setPackageVisibilityFilterEnabled(boolean enabled) {
        PackageAntiDetectionConfig.setPackageVisibilityFilterEnabled(enabled);
    }

    /**
     * 设置检测包列表（隐藏判定时要处理的第三方检测包）。
     *
     * @param detectedPackages 检测包列表
     */
    @Override
    public void setDetectedPackages(List<String> detectedPackages) {
        PackageAntiDetectionConfig.setDetectedPackages(detectedPackages);
    }
}
