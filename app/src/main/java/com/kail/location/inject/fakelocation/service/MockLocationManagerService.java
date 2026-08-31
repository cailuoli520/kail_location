package com.kail.location.inject.fakelocation.service;

import android.content.Context;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.telephony.SubscriptionInfo;
import com.kail.location.inject.fakelocation.InjectDex;
import com.kail.location.inject.utils.LicenseStateManager;
import com.kail.location.inject.utils.MockLocationHookManager;
import com.kail.location.inject.utils.MockStepSensorManager;
import java.util.Arrays;
import java.util.List;
import com.kail.location.inject.fakelocation.aidl.IMockLocationManager;
import com.kail.location.inject.fakelocation.listener.IOnMockLocationListener;
import com.kail.location.inject.fakelocation.model.CellTowerInfo;
import com.kail.location.inject.fakelocation.hook.system.TelephonyRegistryHook;
import com.kail.location.inject.fakelocation.hook.system.WifiServiceHook;

/**
 * 模拟定位服务的 system_server 侧实现（以 oem_location 名注册）。
 *
 * 所有位置 mock 能力委托给 {@link MockLocationHookManager}（GPS/基站/WiFi 模拟），
 * 计步器 mock 委托给 {@link MockStepSensorManager}。
 * 本类作为 binder 服务接收宿主 Kail 应用的指令，并做统一的门槛检查：
 *  - 多数写操作要求 License 可用（LicenseStateManager.isLicenseUsable()），
 *    未授权时不生效或强制清空 mock 数据；
 *  - 模拟坐标按来源区分："route"（路线模拟）与 "rocker"（摇杆），
 *    受 routeMockingEnabled / 远程吊销标记约束；
 *  - 首次 startMockLocation 时按需完成 MockLocationHookManager、WifiServiceHook、
 *    TelephonyRegistryHook 的初始化（幂等，initialized 守卫）。
 */
public class MockLocationManagerService extends IMockLocationManager.Stub {

    /** 路线模拟开关：仅在 License 可用时置 true（初始化于 startMockLocation）。 */
    boolean routeMockingEnabled = false;

    /** 当前生效的 mock 订阅信息（SIM 卡信息，供基带位置绑定的应用读取）。 */
    List<SubscriptionInfo> mockSubscriptionInfo = null;

    /** mock 订阅信息功能开关。 */
    boolean mockSubscriptionInfoEnabled = false;

    /** 服务支持的 mock 模式标记列表（用于 UI 侧展示可用的模式）。 */
    List<String> supportedMockModes = Arrays.asList("1", "3", "5", "7");

    /**
     * 获取"安全应用"列表（这些应用不参与 mock 过滤，直接读取真实数据）。
     *
     * @return 安全包名列表
     */
    @Override
    public List<String> getSafeApps() {
        return MockLocationHookManager.getSafeApps();
    }

    /**
     * 更新授权信息（License 序号 + 设备号），用于刷新模拟定位权限。
     *
     * @param licenseToken 授权令牌
     * @param deviceId     当前设备 ID
     */
    @Override
    public void updateLicenseState(String licenseToken, String deviceId) {
        LicenseStateManager.updateLicenseState(licenseToken, deviceId);
    }

    /**
     * 设置"安全应用"列表。
     *
     * @param safeApps 安全包名列表
     */
    @Override
    public void setSafeApps(List<String> safeApps) {
        MockLocationHookManager.setSafeApps(safeApps);
    }

    /**
     * 开关 mock 订阅信息（SIM 卡信息模拟）。
     * 未授权时强制关闭，不可启用。
     *
     * @param enabled true 启用，false 关闭
     */
    @Override
    public void setMockSubscriptionInfoEnabled(boolean enabled) {
        if (LicenseStateManager.isLicenseUsable()) {
            this.mockSubscriptionInfoEnabled = enabled;
        } else {
            this.mockSubscriptionInfoEnabled = false;
        }
    }

    /**
     * 获取允许触发 mock 的包名列表。
     *
     * @return 允许 mock 的包列表
     */
    @Override
    public List<String> getAllowMockPackages() {
        return MockLocationHookManager.getAllowMockPackages();
    }

    /**
     * 设置允许触发 mock 的包名列表。
     * 未授权时不生效（忽略本次设置）。
     *
     * @param packages 允许 mock 的包列表
     */
    @Override
    public void setAllowMockPackages(List<String> packages) {
        if (LicenseStateManager.isLicenseUsable()) {
            MockLocationHookManager.setAllowMockPackages(packages);
        }
    }

    /**
     * 查询当前是否处于模拟定位状态。
     *
     * @return true 模拟中，false 未模拟
     */
    @Override
    public boolean isMocking() {
        return MockLocationHookManager.isMocking();
    }

    /**
     * 查询是否正在模拟 GPS 状态（卫星数量等）。
     *
     * @return true 模拟中，false 未模拟
     */
    @Override
    public boolean isMockGpsStatus() {
        return MockLocationHookManager.isMockGpsStatus();
    }

    /**
     * 获取当前 mock 的定位坐标。
     *
     * @return 当前模拟位置，未模拟时可能为 null
     */
    @Override
    public Location getMockLocation() {
        return MockLocationHookManager.getMockLocation();
    }

    /**
     * 查询是否处于计步器模拟状态。
     *
     * @return true 模拟中，false 未模拟
     */
    @Override
    public boolean isStepSensorMocking() {
        return MockStepSensorManager.isStepSensorMocking();
    }

    /**
     * 设置模拟基站列表。
     * 未授权时强制清空（置 null）。
     *
     * @param cells 基站信息列表
     */
    @Override
    public void setMockCells(List<CellTowerInfo> cells) {
        if (LicenseStateManager.isLicenseUsable()) {
            MockLocationHookManager.setMockCells(cells);
        } else {
            MockLocationHookManager.setMockCells(null);
        }
    }

    /**
     * 移除一个模拟状态变化监听器。
     *
     * @param listenerBinder 监听器的 binder 句柄（跨 Binder 传递）
     */
    @Override
    public void removeOnMockListener(IBinder listenerBinder) {
        IOnMockLocationListener listener = IOnMockLocationListener.Stub.asInterface(listenerBinder);
        if (listener == null) {
            return;
        }
        MockLocationHookManager.removeOnMockListener(listener);
    }

    /**
     * 开关计步器模拟功能。
     *
     * @param enabled true 启用，false 关闭
     */
    @Override
    public void setSensorFeatureEnabled(boolean enabled) {
        MockStepSensorManager.setSensorFeatureEnabled(enabled);
    }

    /**
     * 设置模拟坐标更新的最小时间间隔。
     *
     * @param intervalMillis 时间间隔（毫秒）
     */
    @Override
    public void setIntervalTimeout(long intervalMillis) {
        MockLocationHookManager.setIntervalTimeout(intervalMillis);
    }

    /**
     * 设置模拟坐标（核心入口）。
     *
     * 模拟中时按坐标来源做额外校验：
     *  - 来源为 "route"（路线模拟）：此模式未被授权（routeMockingEnabled=false）
     *    时直接丢弃；
     *  - 来源为 "rocker"（摇杆）：License 处于远程吊销状态时直接丢弃。
     *
     * @param location 目标模拟位置；null 表示清空
     */
    @Override
    public void setMockLocation(Location location) {
        Bundle extras;
        if (location != null && (extras = location.getExtras()) != null) {
            String locationSource = extras.getString("from", "loc");
            if (MockLocationHookManager.isMocking()) {
                if ("route".equals(locationSource) && !this.routeMockingEnabled) {
                    return;
                }
                if ("rocker".equals(locationSource) && LicenseStateManager.hasRemoteDenial()) {
                    return;
                }
            }
        }
        MockLocationHookManager.setMockLocation(location);
    }

    /**
     * 开关 mock 来源标记（当前实现为空：该能力未启用，恒为 false）。
     *
     * @param enabled 预留参数，未生效
     */
    @Override
    public void setMockSourceTagEnabled(boolean enabled) {
    }

    /**
     * 查询 mock 订阅信息功能是否启用。
     *
     * @return true 启用，false 关闭
     */
    @Override
    public boolean isMockSubscriptionInfoEnabled() {
        return this.mockSubscriptionInfoEnabled;
    }

    /**
     * 获取当前模拟基站列表。
     *
     * @return 基站信息列表；未设置时可能为 null
     */
    @Override
    public List<CellTowerInfo> getMockCells() {
        return MockLocationHookManager.getMockCells();
    }

    /**
     * 设置计步器步数偏移量（在真实步数基础上叠加偏移）。
     *
     * @param stepCount 步数偏移值
     */
    @Override
    public void setStepCountOffset(long stepCount) {
        MockStepSensorManager.setStepCountOffset(stepCount);
    }

    /**
     * 停止模拟定位（清空坐标、恢复真实数据流）。
     */
    @Override
    public void stopMockLocation() {
        MockLocationHookManager.stopMockLocation();
    }

    /**
     * 获取当前 mock 步数（真实步数 + 偏移）。
     *
     * @return 模拟步数
     */
    @Override
    public long getMockStepCount() {
        return MockStepSensorManager.getMockStepCount();
    }

    /**
     * 设置模拟订阅信息（SIM 卡信息）。
     * 未授权时强制清空。
     *
     * @param subscriptionInfo 订阅信息列表
     */
    @Override
    public void setMockSubscriptionInfo(List<SubscriptionInfo> subscriptionInfo) {
        if (LicenseStateManager.isLicenseUsable()) {
            this.mockSubscriptionInfo = subscriptionInfo;
        } else {
            this.mockSubscriptionInfo = null;
        }
    }

    /**
     * 开关 GPS 状态模拟（卫星数量、信号强度等）。
     * 未授权时不可启用，统一置为 false。
     *
     * @param enabled true 启用，false 关闭
     */
    @Override
    public void setMockGpsStatus(boolean enabled) {
        if (!enabled || LicenseStateManager.isLicenseUsable()) {
            MockLocationHookManager.setMockGpsStatus(enabled);
        } else {
            MockLocationHookManager.setMockGpsStatus(false);
        }
    }

    /**
     * 注册一个模拟状态变化监听器。
     *
     * @param listenerBinder 监听器的 binder 句柄（跨 Binder 传递）
     */
    @Override
    public void addOnMockListener(IBinder listenerBinder) {
        IOnMockLocationListener listener = IOnMockLocationListener.Stub.asInterface(listenerBinder);
        if (listener == null) {
            return;
        }
        MockLocationHookManager.addOnMockListener(listener);
    }

    /**
     * 启动计步器模拟。
     * 未授权时不生效。
     */
    @Override
    public void startStepSensorMock() {
        if (LicenseStateManager.isLicenseUsable()) {
            MockStepSensorManager.startStepSensorMock();
        }
    }

    /**
     * 获取模拟坐标更新的最小时间间隔。
     *
     * @return 时间间隔（毫秒）
     */
    @Override
    public long getIntervalTimeout() {
        return MockLocationHookManager.getIntervalTimeout();
    }

    /**
     * 查询计步器模拟功能是否启用。
     *
     * @return true 启用，false 关闭
     */
    @Override
    public boolean isSensorFeatureEnabled() {
        return MockStepSensorManager.isSensorFeatureEnabled();
    }

    /**
     * 设置计步器基准步数（mock 结果 = 基准步数 + 实际计步增量）。
     *
     * @param stepCount 基准步数
     */
    @Override
    public void setBaseStepCount(long stepCount) {
        MockStepSensorManager.setBaseStepCount(stepCount);
    }

    /**
     * 设置计步器模拟速度。
     *
     * @param speed 步频速度值
     */
    @Override
    public void setStepSpeed(float speed) {
        MockStepSensorManager.setStepSpeed(speed);
    }

    /**
     * 查询 mock 来源标记功能是否启用（当前恒为 false）。
     *
     * @return false（未实现）
     */
    @Override
    public boolean isMockSourceTagEnabled() {
        return false;
    }

    /**
     * 停止计步器模拟。
     */
    @Override
    public void stopStepSensorMock() {
        MockStepSensorManager.stopStepSensorMock();
    }

    /**
     * 获取当前模拟订阅信息。
     *
     * @return 订阅信息列表；未设置时可能为 null
     */
    @Override
    public List<SubscriptionInfo> getMockSubscriptionInfo() {
        return this.mockSubscriptionInfo;
    }

    /**
     * 获取计步器模拟速度。
     *
     * @return 步频速度值
     */
    @Override
    public float getStepSpeed() {
        return MockStepSensorManager.getStepSpeed();
    }

    /**
     * 启动模拟定位（核心入口）。
     *
     * 首次调用时（initialized 守卫，幂等）会在此完成整套链路初始化：
     *  - MockLocationHookManager.init：挂载位置相关 hook；
     *  - WifiServiceHook：修正 Wi-Fi 定位数据；
     *  - TelephonyRegistryHook：修正基带/信号定位数据。
     * 同时刷新路由模拟开关（routeMockingEnabled = License 可用）。
     */
    @Override
    public void startMockLocation() {
        this.routeMockingEnabled = LicenseStateManager.isLicenseUsable();
        if (!MockLocationHookManager.initialized) {
            Context context = InjectDex.getApplicationContext();
            MockLocationHookManager.init(context);
            WifiServiceHook.hook(context.getClassLoader());
            TelephonyRegistryHook.hook(context.getClassLoader());
        }
        MockLocationHookManager.startMockLocation();
    }
}
