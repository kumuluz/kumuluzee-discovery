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

import java.net.URL;
import java.util.List;
import java.util.Optional;

/**
 * @author Jan Meznarič
 */
public interface DiscoveryUtil {

    void register(String serviceName, String version, String environment, long ttl, long
            pingInterval, boolean singleton);

    void deregister();

    Optional<List<URL>> getServiceInstances(String serviceName, String version, String
            environment);

    Optional<URL> getServiceInstance(String serviceName, String version, String environment);

    Optional<List<String>> getServiceVersions(String serviceName, String environment);

    void disableServiceInstance(String serviceName, String version, String environment,
                                URL url);

}
