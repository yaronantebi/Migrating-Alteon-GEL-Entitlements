package com.radware.vdirect.ps

import com.radware.alteon.workflow.impl.java.Param

class DeviceInfo {
    DeviceInfo() {}

    static DeviceInfo build(String adcName, String hostName, Integer throughput, String entitlement) {
        DeviceInfo deviceInfo = new DeviceInfo()
        deviceInfo.adcName = adcName + " / " + hostName + " / " + Integer.toString(throughput)
        deviceInfo.entitlement = entitlement
        deviceInfo
    }

    @Param(prompt ='ADC Name')
    String adcName

    @Param(prompt ='entitlement')
    String entitlement
}
