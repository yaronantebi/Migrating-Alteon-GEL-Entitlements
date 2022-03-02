package com.radware.vdirect.ps

class MigrateResults {
    MigrateResults(String deviceName, String sourceEntitlement, String destinationEntitlement, boolean success = null,
                   Integer throughput, String explanation = "", String clearOldGelAllocation = "",
                    String HostId = "", String color = ""){

        this.deviceName = deviceName
        this.sourceEntitlement = sourceEntitlement
        this.destinationEntitlement = destinationEntitlement
        this.success = success
        this.throughput = throughput
        this.explanation = explanation
        this.clearOldGelAllocation = clearOldGelAllocation
        this.HostId = HostId
        this.color = color
    }
    String deviceName
    String sourceEntitlement
    String destinationEntitlement
    boolean success
    String throughput
    String explanation
    String clearOldGelAllocation
    String HostId
    String color
}
