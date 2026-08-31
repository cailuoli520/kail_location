package com.kail.location.inject.fakelocation.service;

import com.kail.location.inject.fakelocation.InjectDex;
import com.kail.location.inject.utils.LicenseStateManager;
import com.kail.location.inject.utils.MockWifiConfigManager;
import java.util.List;
import com.kail.location.inject.fakelocation.aidl.IMockWifiManager;
import com.kail.location.inject.fakelocation.model.MockWifiNetwork;
import com.kail.location.inject.fakelocation.hook.system.WifiServiceHook;

/**
 * 模拟 Wi-Fi 服务的 system_server 侧实现（以 oem_wifi 名注册）。
 *
 * 配置（允许 mock 的包、模拟 Wi-Fi 网络列表、主模拟网络等）全部委托给
 * {@link MockWifiConfigManager}；实际生效由 {@link WifiServiceHook} 完成——
 * 它拦截扫描结果（scanResults）与网络连接信息（connectionInfo），
 * 用配置里的模拟网络替换真实结果。本类负责 binder 指令转发、幂等初始化
 * hook（scanResultsHooked / connectionInfoHooked 守卫）以及 License 门槛。
 */
public class MockWifiManagerService extends IMockWifiManager.Stub {

    /**
     * 获取定向允许规则列表（按包定向的 mock 规则）。
     *
     * @return 定向允许规则列表
     */
    @Override
    public List<String> getScopedAllowMockRules() {
        return MockWifiConfigManager.getScopedAllowMockRules();
    }

    /**
     * 更新授权信息（License 序号 + 设备号），用于刷新模拟 Wi-Fi 权能。
     *
     * @param licenseToken 授权令牌
     * @param deviceId     当前设备 ID
     */
    @Override
    public void updateLicenseState(String licenseToken, String deviceId) {
        LicenseStateManager.updateLicenseState(licenseToken, deviceId);
    }

    /**
     * 设置定向允许规则列表。
     *
     * @param scopedAllowMockRules 定向允许规则列表
     */
    @Override
    public void setScopedAllowMockRules(List<String> scopedAllowMockRules) {
        MockWifiConfigManager.setScopedAllowMockRules(scopedAllowMockRules);
    }

    /**
     * 获取允许触发 mock 的包名列表。
     *
     * @return 允许 mock 的包列表
     */
    @Override
    public List<String> getAllowMockPackages() {
        return MockWifiConfigManager.getAllowMockPackages();
    }

    /**
     * 设置允许触发 mock 的包名列表。
     *
     * @param packages 允许 mock 的包列表
     */
    @Override
    public void setAllowMockPackages(List<String> packages) {
        MockWifiConfigManager.setAllowMockPackages(packages);
    }

    /**
     * 查询模拟 Wi-Fi 功能是否启用。
     *
     * @return true 启用，false 未启用
     */
    @Override
    public boolean isMockWifiEnabled() {
        return MockWifiConfigManager.isMockWifiEnabled();
    }

    /**
     * 获取当前配置的模拟 Wi-Fi 网络列表（供扫描结果替换使用）。
     *
     * @return 模拟网络列表
     */
    @Override
    public List<MockWifiNetwork> getMockWifiNetworks() {
        return MockWifiConfigManager.getMockWifiNetworks();
    }

    /**
     * 获取主模拟 Wi-Fi 网络（用于连接状态/获取名称、BSSID 的替换）。
     *
     * @return 主模拟网络；未设置时可能为 null
     */
    @Override
    public MockWifiNetwork getPrimaryMockWifiNetwork() {
        return MockWifiConfigManager.getPrimaryMockWifiNetwork();
    }

    /**
     * 设置主模拟 Wi-Fi 网络。
     *
     * @param network 主模拟网络
     */
    @Override
    public void setPrimaryMockWifiNetwork(MockWifiNetwork network) {
        MockWifiConfigManager.setPrimaryMockWifiNetwork(network);
    }

    /**
     * 启动模拟 Wi-Fi（核心入口）。
     *
     * 仅在 License 可用时生效，且按需（幂等守卫）完成 hook 初始化：
     *  - WifiServiceHook.hook：拦截 scanResults 扫描结果；
     *  - WifiServiceHook.hookGetConnectionInfo：拦截连接信息。
     * 最后置 mockWifiEnabled = true。
     */
    @Override
    public void startMockWifi() {
        if (LicenseStateManager.isLicenseUsable()) {
            if (!WifiServiceHook.scanResultsHooked) {
                WifiServiceHook.hook(InjectDex.getApplicationContext().getClassLoader());
            }
            if (!WifiServiceHook.connectionInfoHooked) {
                WifiServiceHook.hookGetConnectionInfo(InjectDex.getApplicationContext().getClassLoader());
            }
            MockWifiConfigManager.setMockWifiEnabled(true);
        }
    }

    /**
     * 配置模拟 Wi-Fi 网络列表（在下一次调用 startMockWifi 后生效）。
     *
     * @param networks 模拟网络列表
     */
    @Override
    public void setMockWifiNetworks(List<MockWifiNetwork> networks) {
        MockWifiConfigManager.setMockWifiNetworks(networks);
    }

    /**
     * 停止模拟 Wi-Fi（仅关闭开关，即使已挂上的 hook 保留但不生效）。
     */
    @Override
    public void stopMockWifi() {
        MockWifiConfigManager.setMockWifiEnabled(false);
    }
}
