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

import com.kumuluz.ee.configuration.utils.ConfigurationUtil;

import java.util.Optional;

/**
 * Util class for service registration.
 *
 * @author Urban Malc
 * @author Jan Meznaric
 * @since 1.0.0
 */
public class InitializationUtils {

    private InitializationUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static int getStartRetryDelayMs(ConfigurationUtil configurationUtil, String implementation) {
        Optional<Integer> universalConfig = configurationUtil
                .getInteger("kumuluzee.discovery.start-retry-delay-ms");
        if (universalConfig.isPresent()) {
            return universalConfig.get();
        } else {
            return configurationUtil.getInteger("kumuluzee.discovery." + implementation + ".start-retry-delay-ms")
                    .orElse(500);
        }
    }

    public static int getMaxRetryDelayMs(ConfigurationUtil configurationUtil, String implementation) {
        Optional<Integer> universalConfig = configurationUtil.getInteger("kumuluzee.discovery.max-retry-delay-ms");
        if (universalConfig.isPresent()) {
            return universalConfig.get();
        } else {
            return configurationUtil.getInteger("kumuluzee.discovery." + implementation + ".max-retry-delay-ms")
                    .orElse(900000);
        }
    }
}
