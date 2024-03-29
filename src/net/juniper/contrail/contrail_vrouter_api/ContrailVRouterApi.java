/**
 * Copyright (c) 2014 Juniper Networks, Inc. All rights reserved.
 */
package net.juniper.contrail.contrail_vrouter_api;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.io.IOException;
import org.apache.log4j.Logger;
import net.juniper.contrail.api.ApiConnector;
import net.juniper.contrail.api.ApiConnectorFactory;
import net.juniper.contrail.api.Port;
import net.juniper.contrail.api.EnablePort;
import net.juniper.contrail.api.DisablePort;

public class ContrailVRouterApi {
    private static final Logger s_logger =
            Logger.getLogger(ContrailVRouterApi.class);

    private final String serverAddress;
    private final int serverPort;
    private final String username;
    private final String password;
    private final String tenant;
    private final String authtype;
    private final String authurl;

    protected ApiConnector apiConnector;
    private ConcurrentMap<String, Port> ports2Add;
    private Queue<String> ports2Delete;
    private boolean alive;
    private boolean active = true;
    private boolean syncFailed;

    public ContrailVRouterApi(String serverAddress, int serverPort) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.username = null;
        this.password = null;
        this.tenant = null;
        this.authtype = null;
        this.authurl = null;
        this.ports2Add = new ConcurrentHashMap<String, Port>();
        this.ports2Delete = new ConcurrentLinkedQueue<String>();
    }

    public ContrailVRouterApi(String serverAddress, int serverPort,
            String username, String password, String tenant,
            String authtype, String authurl) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.username = username;
        this.password = password;
        this.tenant   = tenant;
        this.authtype = authtype;
        this.authurl  = authurl;
        this.ports2Add = new ConcurrentHashMap<String, Port>();
        this.ports2Delete = new ConcurrentLinkedQueue<String>();
    }

    public boolean isServerAlive() {
        if (apiConnector == null) {
            apiConnector = ApiConnectorFactory.build(serverAddress,
                                                     serverPort);
            if (authurl != null || username != null) {
                apiConnector.credentials(username, password)
                            .tenantName(tenant)
                            .authServer(authtype, authurl);
            }
            if (apiConnector == null) {
                s_logger.warn(this + " failed to create ApiConnector.. retry later");
                alive = false;
                return false;
            }
            s_logger.info(this + " is alive. Got the pulse..");
        }

        alive = true;
        return true;
    }

    public boolean getAlive() {
        return alive;
    }

    public boolean getActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    protected void setApiConnector(ApiConnector apiConnector) {
        this.apiConnector = apiConnector;
    }

    @Override
    public String toString() {
        return "Vrouter" + serverAddress + ":" + serverPort;
    }

    /**
     * Get outstanding list of ports to be added
     * @return Port Map
     */
    public ConcurrentMap<String, Port> getPorts2Add() {
        return ports2Add;
    }

    /**
     * Get outstanding list of ports to be deleted
     */
    public Queue<String> getPorts2Delete() {
        return ports2Delete;
    }

    /**
     * Add a port to the vrouter agent.
     *
     * @param vif_uuid         String of the VIF/Port
     * @param vm_uuid          String of the instance
     * @param interface_name   Name of the VIF/Port
     * @param interface_ip     IP address associated with the VIF
     * @param mac_address      MAC address of the VIF
     * @param network_uuid     String of the associated virtual network
     * @param project_uuid     String of the associated project
     */
    public boolean addPort(String vif_uuid, String vm_uuid, String interface_name,
            String interface_ip, String mac_address, String network_uuid, short primaryVlanId,
            short isolatedVlanId, String vm_name,
            String project_uuid) {
        Port aport = new Port();
        aport.setUuid(vif_uuid);
        aport.setName(vif_uuid);
        aport.setId(vif_uuid);
        aport.setInstance_id(vm_uuid);
        aport.setSystem_name(interface_name);
        aport.setIp_address(interface_ip);
        aport.setMac_address(mac_address);
        aport.setVn_id(network_uuid);
        aport.setTx_vlan_id(primaryVlanId);
        aport.setRx_vlan_id(isolatedVlanId);
        aport.setDisplay_name(vm_name);
        aport.setVm_project_id(project_uuid);

        if (!active) {
            ports2Add.put(vif_uuid, aport);

            s_logger.warn(this +
                    " is not active, addPort: " + aport.getUuid() + "(" + aport.getSystem_name() + ") "
                    + "failed, will retry later");
            return false;
        }

        if (!isServerAlive()) {
            ports2Add.put(vif_uuid, aport);

            s_logger.warn(this +
                    " is not alive, addPort: " + aport.getUuid() + "(" + aport.getSystem_name() + ") "
                    + "failed, will retry later");
            return false;
        }
        if (syncFailed) {
            if (!sync()) {
                ports2Add.put(vif_uuid, aport);

                s_logger.warn(this +
                        " addPort: " + aport.getUuid() + "(" + aport.getSystem_name() + ") "
                        + "failed, will retry later");
                return false;
            }
            retryFailedPorts();
        }
        if (!addPortInternal(aport)) {
            ports2Add.put(vif_uuid, aport);
            return false;
        }
        return true;
    }

    private boolean addPortInternal(Port aport) {
        try {
            apiConnector.create(aport);
        } catch (IOException e) {
               s_logger.warn(this +
                    " addPort: " + aport.getUuid()  + "(" +
                    aport.getSystem_name()  + ") Exception: " + e.getMessage());
            return false;
        }

        return true;
    }


    private boolean enablePortInternal(EnablePort enable_port) {
        try {
            apiConnector.update(enable_port);

        } catch (IOException e) {
               s_logger.warn(this +
                    " enablePort: " + enable_port.getUuid()  + "(" +
                    enable_port.getSystem_name()  + ") Exception: " + e.getMessage());
            return false;
        }

        return true;
    }

    public boolean enablePort(String vif_uuid, String interface_name) {
        EnablePort enable_port = new EnablePort();
        enable_port.setUuid(vif_uuid);
        enable_port.setName(vif_uuid);
        enable_port.setId(vif_uuid);
        enable_port.setSystem_name(interface_name);

        s_logger.info(this + "enablePort call in ContrailVRouterApi");

        if (!enablePortInternal(enable_port)) {
            s_logger.info(this + " enablePort: " + enable_port.getUuid() + "(" +
                    enable_port.getSystem_name() + ")" + "failed");
            return false;
        }

        return true;
    }

    private boolean disablePortInternal(DisablePort disable_port) {
        try {
            apiConnector.update(disable_port);

        } catch (IOException e) {
               s_logger.warn(this +
                    " disablePort: " + disable_port.getUuid()  + "(" +
                    disable_port.getSystem_name()  + ") Exception: " + e.getMessage());
            return false;
        }

        return true;
    }

    public boolean disablePort(String vif_uuid, String interface_name) {
        DisablePort disable_port = new DisablePort();
        disable_port.setUuid(vif_uuid);
        disable_port.setName(vif_uuid);
        disable_port.setId(vif_uuid);
        disable_port.setSystem_name(interface_name);

        s_logger.info(this + "disablePort call in ContrailVRouterApi");

        if (!disablePortInternal(disable_port)) {
            s_logger.info(this + " disablePort: " + disable_port.getUuid() + "(" +
                    disable_port.getSystem_name() + ")" + "failed");
            return false;
        }

        return true;
    }

    /**
     * Delete a port from the agent.
     *
     * @param uuid  String of the VIF/Port
     */
    public boolean deletePort(String uuid) {
         if (ports2Add.containsKey(uuid)) {
             ports2Add.remove(uuid);
         }

         if (!active) {
             ports2Delete.add(uuid);

             s_logger.warn(this +
                     " is not active, deletePort: " + uuid + " failed, will retry later");
             return false;
         }

         if (!isServerAlive()) {
             ports2Delete.add(uuid);

             s_logger.warn(this +
                     " is not alive, deletePort: " + uuid + " failed, will retry later");
             return false;
        }
        if (syncFailed) {
             if (!sync()) {
                 ports2Delete.add(uuid);
                 s_logger.warn(this +
                          " deletePort: " + uuid
                          + "failed, will retry later");
                  return false;
              }
             retryFailedPorts();
        }
        if (!deletePortInternal(uuid)) {
            ports2Delete.add(uuid);
            return false;
        }

        return true;
    }

    private boolean deletePortInternal(String uuid) {
        try {
            apiConnector.delete(Port.class, uuid.toString());
        } catch (IOException e) {

            s_logger.warn(this +
                    " deletePort: " + uuid +
                    " Exception: " + e.getMessage());
            return false;
        }

        return true;
    }

    public boolean sync() {

        if (!isServerAlive()) {
            s_logger.warn(this +
                    " sync failed, vrouter is not alive");
            syncFailed = true;
            return false;
        }
        try {
            boolean res = apiConnector.sync("/syncports").isSuccess();

            if (!res) {
                s_logger.warn(this + " sync request FAILED");
                syncFailed = true;
                return false;
            }
        } catch (IOException e) {
            s_logger.warn(this +
                    " sync failed, exception: " + e.getMessage());
            syncFailed = true;
            return false;
        }
        syncFailed = false;

        return true;
    }

    private boolean retryFailedPorts() {
        for (String uuid: ports2Delete) {
            if (deletePortInternal(uuid)) {
                ports2Delete.remove(uuid);
            }
        }

        for (Map.Entry<String, Port> entry: ports2Add.entrySet()) {
            if (addPortInternal(entry.getValue())) {
                ports2Add.remove(entry.getKey());
            }
        }
        return true;
    }

    public boolean periodicCheck() {
        if (!isServerAlive()) {
            s_logger.warn(this + " not reachable");
            return false;
        }
        if (syncFailed) {
            if (!sync()) {
                s_logger.warn(this + " is alive, but sync request fails");
                return false;
            }
        }

        if (!retryFailedPorts()) {
            s_logger.warn(this + ": provisioning of one or more ports failed");
        }
        return true;
    }
}
