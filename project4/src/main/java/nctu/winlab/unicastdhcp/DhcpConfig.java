package nctu.winlab.unicastdhcp;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.Config;

public class DhcpConfig extends Config<ApplicationId> {

    public static final String SERVER_LOCATION = "serverLocation";

    @Override
    public boolean isValid() {
        return hasOnlyFields(SERVER_LOCATION);
    }

    public ConnectPoint getServerLocation() {
        String serverLocation = get(SERVER_LOCATION, null);
        if (serverLocation == null) {
            return null;
        }
        String[] split = serverLocation.split("/");
        return new ConnectPoint(DeviceId.deviceId(split[0]), PortNumber.fromString(split[1]));
    }
}
