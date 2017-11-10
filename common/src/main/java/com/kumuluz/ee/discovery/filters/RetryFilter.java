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
package com.kumuluz.ee.discovery.filters;

import javax.ws.rs.client.*;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.logging.Logger;

/**
 * Retries all available URLs before failing
 *
 * @author Jan Meznarič
 * @author Urban Malc
 */
public class RetryFilter implements ClientRequestFilter, ClientResponseFilter {

    private static final Logger log = Logger.getLogger(RetryFilter.class.getName());

    private static final String nextUrlProperty = "kumuluzee-discovery-next-url";

    private List<URL> urls;
    private Integer firstUrlIndex;

    public RetryFilter(List<URL> urlList, Integer firstUrlIndex) {
        this.urls = urlList;
        this.firstUrlIndex = firstUrlIndex;
    }

    /**
     * Request filter.
     */
    @Override
    public void filter(ClientRequestContext clientRequestContext) throws IOException {
        Object nextUrlObj = clientRequestContext.getProperty(nextUrlProperty);
        if (nextUrlObj instanceof Integer) {
            clientRequestContext.setProperty(nextUrlProperty, ((Integer) nextUrlObj + 1) % urls.size());
        } else {
            clientRequestContext.setProperty(nextUrlProperty, (firstUrlIndex + 1) % urls.size());
        }
    }

    /**
     * Response filter.
     */
    @Override
    public void filter(ClientRequestContext clientRequestContext, ClientResponseContext clientResponseContext)
            throws IOException {
        Object nextUrlIndexObj = clientRequestContext.getProperty(nextUrlProperty);
        if (nextUrlIndexObj instanceof Integer) {
            Integer nextUrlIndex = (Integer) nextUrlIndexObj;
            boolean success = false;
            while (clientResponseContext.getStatusInfo().getFamily().equals(Response.Status.Family.SERVER_ERROR) &&
                    !(nextUrlIndex).equals(firstUrlIndex) && !success) {
                // get new URI
                Integer previousUrlIndex = (Integer) nextUrlIndexObj - 1;
                if (previousUrlIndex < 0) {
                    previousUrlIndex = urls.size() - 1;
                }
                String previousUrl = urls.get(previousUrlIndex).toString();
                String nextUrl = urls.get((Integer) nextUrlIndexObj).toString();
                Client client = clientRequestContext.getClient();
                String previousUri = clientRequestContext.getUri().toString();
                try {
                    URI newUri = new URI(nextUrl + previousUri.substring(previousUrl.length()));
                    log.warning("Status code " + clientResponseContext.getStatus() + " returned from " +
                            clientRequestContext.getUri() + ". Retrying on " + newUri);

                    // Create new request with same data
                    WebTarget newTarget = client.target(newUri);
                    Invocation.Builder builder = newTarget.request().headers(clientRequestContext.getHeaders());
                    for (String propertyName : clientRequestContext.getPropertyNames()) {
                        builder.property(propertyName, clientRequestContext.getProperty(propertyName));
                    }
                    String method = clientRequestContext.getMethod();
                    Invocation invocation;
                    if (clientRequestContext.hasEntity()) {
                        Entity en = Entity.entity(clientRequestContext.getEntity(),
                                clientRequestContext.getMediaType(),
                                clientRequestContext.getEntityAnnotations());
                        invocation = builder.build(method, en);
                    } else {
                        invocation = builder.build(method);
                    }
                    Response response = invocation.invoke();

                    // Copy response data
                    clientResponseContext.setStatusInfo(response.getStatusInfo());
                    clientResponseContext.setEntityStream((InputStream) response.getEntity());
                    success = true;
                } catch (URISyntaxException ignored) {
                } finally {
                    nextUrlIndex = (nextUrlIndex + 1) % urls.size();
                }
            }
        }
    }
}
