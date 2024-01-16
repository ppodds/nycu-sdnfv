package nycu.sdnfv.vrouter;

import java.util.List;

import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.Config;

public class VRouterConfig extends Config<ApplicationId> {

    public VRouterConfig() {
        super();
    }

    public static final String QUAGGA = "quagga";
    public static final String QUAGGA_MAC = "quagga-mac";
    public static final String VIRTUAL_IP = "virtual-ip";
    public static final String VIRTUAL_MAC = "virtual-mac";
    public static final String PEERS = "peers";

    @Override
    public boolean isValid() {
        return hasOnlyFields(QUAGGA, QUAGGA_MAC, VIRTUAL_IP, VIRTUAL_MAC, PEERS);
    }

    public ConnectPoint getQuagga() {
        String quagga = get(QUAGGA, null);
        if (quagga == null) {
            return null;
        }
        String[] split = quagga.split("/");
        return new ConnectPoint(DeviceId.deviceId(split[0]), PortNumber.fromString(split[1]));
    }

    public MacAddress getQuaggaMac() {
        String quaggaMac = get(QUAGGA_MAC, null);
        if (quaggaMac == null) {
            return null;
        }
        return MacAddress.valueOf(quaggaMac);
    }

    public Ip4Address getVirtualIp() {
        String virtualIp = get(VIRTUAL_IP, null);
        if (virtualIp == null) {
            return null;
        }
        return Ip4Address.valueOf(virtualIp);
    }

    public MacAddress getVirtualMac() {
        String virtualMac = get(VIRTUAL_MAC, null);
        if (virtualMac == null) {
            return null;
        }
        return MacAddress.valueOf(virtualMac);
    }

    public List<Ip4Address> getPeers() {
        return getList(PEERS, (addrStr) -> Ip4Address.valueOf(addrStr));
    }
}