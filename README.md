# GEL Migration Tool

Migrating Alteon between GEL Entitlements.

## Downloading

Download the release version of this project 'gelMigrationTool.zip' and upload it to vDirect.

## How to install

1. Upload the zip file as is to vDirect workflow template (Inventory->workflow template).
2. create a workflow instance from the workflow template.
3. From this point forward you can re-use the workflow instance for each run operation.

## How to Use

## Shared Actions Info

1. **Verify Entitlement Capacity Availability** – It checks on destination target LLS if there is enough throughput for entitlement to contain this ADC device.
2. **Identify Dest Alteon Name by its IP** – Upon different ADC container name in target LLS, it will look on target LLS for the name by the IP address.
3. **Verify Entitlement Type Compatibility** – It will check destination and source entitlement have the same package type.

### Migrate Different LLS Server
1. Alteon Array - Select Alteon, one or more to be migrated.
2. New Entitlement - New Entitlement to be used
> Source\Destination LLS
3. LLS IP
4. LLS User Name
5. LLS Password
6. Auto find destination LLS Alteon device Name - Let the tool find the Alteon name on remote LLS.

![](https://i.imgur.com/cSY3e7X.jpg)

### Migrate Tool (Same LLS Server)
1. Alteon Array - Select Alteon, one or more to be migrated.
2. vDirect User Name
3. vDirect Password
4. New Entitlement - New Entitlement to be used

![](https://i.imgur.com/3qBekX7.png)

## Outputs
- HTML

![](https://i.imgur.com/35VfUi4.jpg)

![](https://i.imgur.com/yyiAazK.png)

- RAW

![](https://i.imgur.com/cIbu5am.jpg)

![](https://i.imgur.com/W2DANqS.png)

## Contributing
Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

## CopyRights
Copyright 2021 Radware – All Rights Reserved

## License
[Apache2.0](https://choosealicense.com/licenses/apache-2.0/)

## Disclaimer
There is no warranty, expressed or implied, associated with this product.
Use at your own risk.
