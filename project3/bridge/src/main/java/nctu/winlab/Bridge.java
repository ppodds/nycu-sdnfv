/*
 * Copyright 2023-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.HashMap;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true, service = { Bridge.class }, property = {
})
public class Bridge {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    private ApplicationId appId;

    private HashMap<DeviceId, HashMap<MacAddress, PortNumber>> forwardingTable;

    private BridgePacketProcessor processor = new BridgePacketProcessor();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.bridge");
        // cfgService.registerProperties(getClass());
        forwardingTable = new HashMap<>();
        packetService.addProcessor(processor, 2);
        requestIntercepts();
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        // cfgService.unregisterProperties(getClass(), false);
        withdrawIntercepts();
        flowRuleService.removeFlowRulesById(appId);
        packetService.removeProcessor(processor);
        processor = null;
        forwardingTable = null;
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();
        log.info("Reconfigured");
    }

    private void requestIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private void withdrawIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private class BridgePacketProcessor implements PacketProcessor {

        private void updateForwardingTable(HashMap<DeviceId, HashMap<MacAddress, PortNumber>> forwardingTable,
                DeviceId destDeviceId,
                MacAddress sourceMac, MacAddress destMac, PortNumber imcomingPort) {
            HashMap<MacAddress, PortNumber> deviceTable = forwardingTable.get(destDeviceId);
            if (deviceTable == null) {
                deviceTable = new HashMap<>();
                forwardingTable.put(destDeviceId, deviceTable);
            }
            PortNumber srcPort = deviceTable.get(sourceMac);
            if (srcPort == null) {
                srcPort = imcomingPort;
                deviceTable.put(sourceMac, srcPort);
                log.info("Add an entry to the port table of `" + destDeviceId + "`. MacAddress: `" + sourceMac
                        + "` => Port: `" + srcPort + "`");
            }
        }

        private void packetOut(PacketContext context, PortNumber port) {
            context.treatmentBuilder().setOutput(port);
            context.send();
        }

        private void flood(PacketContext context) {
            packetOut(context, PortNumber.FLOOD);
        }

        private void installRule(PacketContext context, MacAddress srcMacAddress, MacAddress destMacAddress,
                PortNumber portNumber) {
            TrafficSelector selector = DefaultTrafficSelector.builder().matchEthSrc(srcMacAddress)
                    .matchEthDst(destMacAddress).build();
            TrafficTreatment treatment = context.treatmentBuilder().setOutput(portNumber).build();
            ForwardingObjective objective = DefaultForwardingObjective.builder().withSelector(selector)
                    .withTreatment(treatment).withPriority(30).makeTemporary(30)
                    .withFlag(ForwardingObjective.Flag.VERSATILE).fromApp(appId).add();
            flowObjectiveService.forward(context.inPacket().receivedFrom().deviceId(), objective);
            packetOut(context, portNumber);
        }

        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            if (ethPkt == null) {
                return;
            }

            HostId id = HostId.hostId(ethPkt.getDestinationMAC(), VlanId.vlanId(ethPkt.getVlanID()));

            MacAddress srcMacAddress = ethPkt.getSourceMAC();
            MacAddress destMacAddress = ethPkt.getDestinationMAC();
            ConnectPoint receivedFrom = pkt.receivedFrom();
            DeviceId switchDeviceId = receivedFrom.deviceId();

            // log.info("Source MAC: " + srcMacAddress.toString());
            // log.info("Dest MAC: " + destMacAddress.toString());
            // log.info("Dest Host ID: " + id.toString());
            // log.info("Eth Received From: " + receivedFrom.toString());
            // log.info("Switch Device Id: " + switchDeviceId.toString());

            updateForwardingTable(forwardingTable, switchDeviceId, srcMacAddress, destMacAddress,
                    receivedFrom.port());

            PortNumber destPortNumber = forwardingTable.get(switchDeviceId).get(destMacAddress);
            if (destPortNumber == null) {
                flood(context);
                log.info("MAC Address `" + destMacAddress + "` is missed on `" + switchDeviceId
                        + "`. Flood the packet.");
                return;
            }

            installRule(context, srcMacAddress, destMacAddress, destPortNumber);
            log.info("Mac Address `" + destMacAddress + "` is matched on `" + switchDeviceId
                    + "`. Install a flow rule.");
        }

    }

}
