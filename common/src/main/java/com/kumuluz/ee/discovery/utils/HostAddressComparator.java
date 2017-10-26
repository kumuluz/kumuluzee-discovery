/*
 *  Copyright (c) 2014-2017 Kumuluz and/or its affiliates
 *  and other contributors as indicated by the @author tags and
 *  the contributor list.
 *
 *  Licensed under the MIT License (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://opensource.org/licenses/MIT
 *
 *  The software is provided "AS IS", WITHOUT WARRANTY OF ANY KIND, express or
 *  implied, including but not limited to the warranties of merchantability,
 *  fitness for a particular purpose and noninfringement. in no event shall the
 *  authors or copyright holders be liable for any claim, damages or other
 *  liability, whether in an action of contract, tort or otherwise, arising from,
 *  out of or in connection with the software or the use or other dealings in the
 *  software. See the License for the specific language governing permissions and
 *  limitations under the License.
*/
package com.kumuluz.ee.discovery.utils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Comparator;

/**
 * Host address comparator.
 *
 * @author Urban Malc
 * @author Jan Meznaric
 * @since 1.0.0
 */
public class HostAddressComparator implements Comparator<InetAddress> {

    private static final int PRIORITY_IPV4 = 1;
    private static final int PRIORITY_IPV6 = 2;
    private static final int PRIORITY_IPV6_LINK_LOCAL = 3;
    private static final int PRIORITY_IPV4_LOOPBACK = 4;
    private static final int PRIORITY_IPV6_LOOPBACK = 5;

    @Override
    public int compare(InetAddress a1, InetAddress a2) {
        return Integer.compare(getAddressPriority(a1), getAddressPriority(a2));
    }

    private static int getAddressPriority(InetAddress address) {
        if (address instanceof Inet4Address) {
            if (!address.isLoopbackAddress()) {
                return PRIORITY_IPV4;
            } else {
                return PRIORITY_IPV4_LOOPBACK;
            }
        } else {
            if (address.isLoopbackAddress()) {
                return PRIORITY_IPV6_LOOPBACK;
            }
            if (address.isLinkLocalAddress()) {
                return PRIORITY_IPV6_LINK_LOCAL;
            }

            return PRIORITY_IPV6;
        }
    }
}
