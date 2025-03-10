/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.filter;

import java.net.URI;
import java.util.List;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter.Type;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter.filterRequest;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_CONN_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_HEADER_NAMES;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.PRESERVE_HOST_HEADER_ATTRIBUTE;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.isAlreadyRouted;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setAlreadyRouted;

/**
 * @author Spencer Gibb
 * @author Biju Kunjummen
 */
public class NettyRoutingFilter implements GlobalFilter, Ordered {

	private static final Log log = LogFactory.getLog(NettyRoutingFilter.class);

	private final HttpClient httpClient;

	private final ObjectProvider<List<HttpHeadersFilter>> headersFiltersProvider;

	private final HttpClientProperties properties;

	// do not use this headersFilters directly, use getHeadersFilters() instead.
	private volatile List<HttpHeadersFilter> headersFilters;

	public NettyRoutingFilter(HttpClient httpClient,
			ObjectProvider<List<HttpHeadersFilter>> headersFiltersProvider,
			HttpClientProperties properties) {
		this.httpClient = httpClient;
		this.headersFiltersProvider = headersFiltersProvider;
		this.properties = properties;
	}

	public List<HttpHeadersFilter> getHeadersFilters() {
		if (headersFilters == null) {
			headersFilters = headersFiltersProvider.getIfAvailable();
		}
		return headersFilters;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	@SuppressWarnings("Duplicates")
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);

		String scheme = requestUrl.getScheme();
		if (isAlreadyRouted(exchange)
				|| (!"http".equals(scheme) && !"https".equals(scheme))) {
			return chain.filter(exchange);
		}
		setAlreadyRouted(exchange);

		ServerHttpRequest request = exchange.getRequest();

		final HttpMethod method = HttpMethod.valueOf(request.getMethodValue());
		/**
		 * 这里有个坑，这个方法默认会把url进行UTF-8的URLEncode，可能会导致GBK接口的解析失败
		 * 2.1.3 Release版本中，这里为：final String url = requestUrl.toString();  这种不会有这种编码问题
		 */
		final String url = requestUrl.toASCIIString();

		HttpHeaders filtered = filterRequest(getHeadersFilters(), exchange);

		final DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();
		filtered.forEach(httpHeaders::set);

		boolean preserveHost = exchange
				.getAttributeOrDefault(PRESERVE_HOST_HEADER_ATTRIBUTE, false);

		Flux<HttpClientResponse> responseFlux = this.httpClient.headers(headers -> {
			headers.add(httpHeaders);
			if (preserveHost) {
				String host = request.getHeaders().getFirst(HttpHeaders.HOST);
				headers.add(HttpHeaders.HOST, host);
			}
			else {
				// let Netty set it based on hostname
				headers.remove(HttpHeaders.HOST);
			}
		}).request(method).uri(url).send((req, nettyOutbound) -> {
			if (log.isTraceEnabled()) {
				nettyOutbound.withConnection(connection -> log.trace(
						"outbound route: " + connection.channel().id().asShortText()
								+ ", inbound: " + exchange.getLogPrefix()));
			}
			return nettyOutbound.send(request.getBody()
					.map(dataBuffer -> ((NettyDataBuffer) dataBuffer).getNativeBuffer()));
		}).responseConnection((res, connection) -> {

			// Defer committing the response until all route filters have run
			// Put client response as ServerWebExchange attribute and write
			// response later NettyWriteResponseFilter
			exchange.getAttributes().put(CLIENT_RESPONSE_ATTR, res);
			exchange.getAttributes().put(CLIENT_RESPONSE_CONN_ATTR, connection);

			ServerHttpResponse response = exchange.getResponse();
			// put headers and status so filters can modify the response
			HttpHeaders headers = new HttpHeaders();

			res.responseHeaders()
					.forEach(entry -> headers.add(entry.getKey(), entry.getValue()));

			String contentTypeValue = headers.getFirst(HttpHeaders.CONTENT_TYPE);
			if (StringUtils.hasLength(contentTypeValue)) {
				exchange.getAttributes().put(ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR,
						contentTypeValue);
			}

			HttpStatus status = HttpStatus.resolve(res.status().code());
			if (status != null) {
				response.setStatusCode(status);
			}
			else if (response instanceof AbstractServerHttpResponse) {
				// https://jira.spring.io/browse/SPR-16748
				((AbstractServerHttpResponse) response)
						.setStatusCodeValue(res.status().code());
			}
			else {
				// TODO: log warning here, not throw error?
				throw new IllegalStateException("Unable to set status code on response: "
						+ res.status().code() + ", " + response.getClass());
			}

			// make sure headers filters run after setting status so it is
			// available in response
			HttpHeaders filteredResponseHeaders = HttpHeadersFilter
					.filter(getHeadersFilters(), headers, exchange, Type.RESPONSE);

			if (!filteredResponseHeaders.containsKey(HttpHeaders.TRANSFER_ENCODING)
					&& filteredResponseHeaders.containsKey(HttpHeaders.CONTENT_LENGTH)) {
				// It is not valid to have both the transfer-encoding header and
				// the content-length header.
				// Remove the transfer-encoding header in the response if the
				// content-length header is present.
				response.getHeaders().remove(HttpHeaders.TRANSFER_ENCODING);
			}

			exchange.getAttributes().put(CLIENT_RESPONSE_HEADER_NAMES,
					filteredResponseHeaders.keySet());

			response.getHeaders().putAll(filteredResponseHeaders);

			return Mono.just(res);
		});

		if (properties.getResponseTimeout() != null) {
			responseFlux = responseFlux.timeout(properties.getResponseTimeout(),
					Mono.error(new TimeoutException("Response took longer than timeout: "
							+ properties.getResponseTimeout())))
					.onErrorMap(TimeoutException.class,
							th -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
									th.getMessage(), th));
		}

		return responseFlux.then(chain.filter(exchange));
	}

}
