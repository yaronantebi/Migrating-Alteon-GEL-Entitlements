package com.radware.vdirect.ps

import com.radware.alteon.beans.adc.AgLMLicenseConfig
import com.radware.alteon.beans.adc.AgLMLicenseInfo
import com.radware.alteon.beans.adc.AgLicenseCapacityInfoTableEntry
import com.radware.alteon.beans.adc.AgLicenseOper
import com.radware.alteon.beans.adc.AgSystem
import com.radware.alteon.workflow.impl.DeviceConnection
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static groovyx.net.http.Method.GET

class Helpers {

    public static final Logger log = LoggerFactory.getLogger(GELMigrationTool.class)

    static getAgLMLicenseInfo(DeviceConnection adc){
        AgLMLicenseInfo agLMLicenseInfo = new AgLMLicenseInfo()
        AgLMLicenseInfo agLMLicenseInfoBean = (AgLMLicenseInfo) adc.read(agLMLicenseInfo)
        return agLMLicenseInfoBean
    }

    static getAgLMLicenseConfig(DeviceConnection adc){
        AgLMLicenseConfig agLMLicenseConfig = new AgLMLicenseConfig()
        AgLMLicenseConfig agLMLicenseConfigBean = (AgLMLicenseConfig) adc.read(agLMLicenseConfig)
        return agLMLicenseConfigBean
    }

    static getAgLicenseOper(DeviceConnection adc){
        AgLicenseOper agLicenseOper = new AgLicenseOper()
        AgLicenseOper agLicenseOperBean = (AgLicenseOper) adc.read(agLicenseOper)
        return agLicenseOperBean
    }

    static Boolean isEqualEntitlementType(String type1, String type2){
        return type1.equalsIgnoreCase(type2)
    }

    static getAgSystem(DeviceConnection adc){
        AgSystem agSystem = new AgSystem()
        AgSystem agSystemBean = (AgSystem) adc.read(agSystem)
        return agSystemBean
    }

    static getAgLicenseCapacityInfoTableEntry(DeviceConnection adc){
        AgLicenseCapacityInfoTableEntry agLicenseCapacityInfoTableEntry = new AgLicenseCapacityInfoTableEntry(licenseCapacityInfoIdx: 9)
        AgLicenseCapacityInfoTableEntry agLicenseCapacityInfoTableEntryBean = (AgLicenseCapacityInfoTableEntry) adc.read(agLicenseCapacityInfoTableEntry)
        return agLicenseCapacityInfoTableEntryBean
    }

    static entitlementCheck(LinkedHashMap entitlementTypeMap, String sourceEntitlement, String newEntitlement,
                            Boolean entitlementType, List<MigrateResults> results,
                            String deviceName){

        String srcPackage = entitlementTypeMap[sourceEntitlement] as String
        String dstPackage = entitlementTypeMap[newEntitlement] as String
        if(!srcPackage){
            log.error String.format("Alteon %s got license from other LLS Server, " +
                    "entitlement type check is not possible, entId: %s",deviceName, sourceEntitlement)
            results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement,
                    false,0, "Source Entitlements type check not available"))
            entitlementType = false
        }else if(!dstPackage){
            log.error String.format("Couldn't find destination Entitlement, " +
                    "entitlement type check is not possible, entId: %s",deviceName, newEntitlement)
            results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement,
                    false,0, "Invalid Destination Entitlement ID"))
            entitlementType = false
        }else{
            log.debug String.format("srcPackage Type: %s", srcPackage.dump())
            log.debug String.format("dstPackage Type: %s", dstPackage.dump())
            if(!isEqualEntitlementType(srcPackage, dstPackage)){
                log.error String.format("Source and Destination Entitlement type is different %s vs %s",
                        entitlementTypeMap[sourceEntitlement].toString(),
                        entitlementTypeMap[newEntitlement].toString())
                results.add(new MigrateResults(deviceName, sourceEntitlement, newEntitlement,
                        false,0, "Entitlements type is different"))
                entitlementType = false
            }
        }
    }

/*    def getEntitlementType(){
        def entitlementTypeMap = [:]
        licenseReport.entitlements.each{ ent ->
            String entId = ent.key
            entitlementTypeMap[entId] = ent.value.package
        }
    }*/

}
