package com.radware.vdirect.ps

class MigrateResults {
    MigrateResults(String deviceName, String sourceEntitlement, String destinationEntitlement, boolean success,
                   Integer throughput, String explanation = ""){

        this.deviceName = deviceName
        this.sourceEntitlement = sourceEntitlement
        this.destinationEntitlement = destinationEntitlement
        this.success = success
        this.throughput = throughput
        this.explanation = explanation
    }
    String deviceName
    String sourceEntitlement
    String destinationEntitlement
    boolean success
    String throughput
    String explanation
}
