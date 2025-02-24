/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytejta.supports.springcloud;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.common.utils.SerializeUtils;
import org.bytesoft.transaction.TransactionParticipant;
import org.bytesoft.transaction.remote.RemoteAddr;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.remote.RemoteNode;
import org.bytesoft.transaction.xa.TransactionXid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

public class SpringCloudCoordinator implements InvocationHandler {
	static final Logger logger = LoggerFactory.getLogger(SpringCloudCoordinator.class);
	static final String CONSTANT_CONTENT_PATH = "org.bytesoft.bytejta.contextpath";

	private String identifier;
	private Environment environment;

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		Class<?> clazz = method.getDeclaringClass();
		String methodName = method.getName();
		Class<?> returnType = method.getReturnType();
		if (Object.class.equals(clazz)) {
			return method.invoke(this, args);
		} else if (TransactionParticipant.class.equals(clazz)) {
			throw new XAException(XAException.XAER_RMFAIL);
		} else if (RemoteCoordinator.class.equals(clazz)) {
			if ("getIdentifier".equals(methodName)) {
				return this.identifier;
			} else if ("getApplication".equals(methodName)) {
				int firstIndex = this.identifier.indexOf(":");
				int lastIndex = this.identifier.lastIndexOf(":");
				return firstIndex <= 0 || lastIndex <= 0 || firstIndex > lastIndex //
						? null : this.identifier.subSequence(firstIndex + 1, lastIndex);
			} else if ("getRemoteAddr".equals(methodName) && RemoteAddr.class.equals(returnType)) {
				return this.identifier == null ? null : CommonUtils.getRemoteAddr(this.identifier);
			} else if ("getRemoteNode".equals(methodName) && RemoteNode.class.equals(returnType)) {
				return this.identifier == null ? null : CommonUtils.getRemoteNode(this.identifier);
			} else {
				throw new XAException(XAException.XAER_RMFAIL);
			}
		} else if (XAResource.class.equals(clazz)) {
			if ("start".equals(methodName)) {
				return null; // return immediately
			} else if ("prepare".equals(methodName)) {
				return this.invokeHttpPostRequest(proxy, method, args);
			} else if ("commit".equals(methodName)) {
				return this.invokeHttpPostRequest(proxy, method, args);
			} else if ("rollback".equals(methodName)) {
				return this.invokeHttpPostRequest(proxy, method, args);
			} else if ("recover".equals(methodName)) {
				return this.invokeTransactionRecover(proxy, method, args);
			} else if ("forget".equals(methodName)) {
				return this.invokeHttpPostRequest(proxy, method, args);
			} else {
				throw new XAException(XAException.XAER_RMFAIL);
			}
		} else {
			throw new IllegalAccessException();
		}
	}

	// 根据方法名, 拼接url, 调用远程服务接口
	public Object invokeHttpPostRequest(Object proxy, Method method, Object[] args) throws Throwable {

		Class<?> returnType = method.getReturnType();
		try {
			RestTemplate transactionRestTemplate = SpringCloudBeanRegistry.getInstance().getRestTemplate();
			RestTemplate restTemplate = transactionRestTemplate == null ? new RestTemplate() : transactionRestTemplate;

			RemoteNode remoteNode = CommonUtils.getRemoteNode(this.identifier);
			String contextPathKey = String.format("%s.%s", CONSTANT_CONTENT_PATH, remoteNode.getServiceKey());
			String contextPath = StringUtils.isBlank(remoteNode.getServiceKey()) //
					? null : StringUtils.trimToEmpty(this.environment.getProperty(contextPathKey));

			StringBuilder ber = new StringBuilder();
			// http://${serverHost}:${serverPort}
			ber.append("http://").append(remoteNode.getServerHost()).append(":").append(remoteNode.getServerPort());

			if (StringUtils.isNotBlank(contextPath) || StringUtils.equals(contextPath, "/")) {
				ber.append(contextPath.startsWith("/") ? "" : "/").append(contextPath);
			} // end-if (StringUtils.isNotBlank(contextPath) || StringUtils.equals(contextPath, "/"))

			// http://${serverHost}:${serverPort}/org/bytesoft/bytejta/
			ber.append("/org/bytesoft/bytejta/");
			// http://${serverHost}:${serverPort}/org/bytesoft/bytejta/${methodName}
			ber.append(method.getName());
			for (int i = 0; i < args.length; i++) {
				Serializable arg = (Serializable) args[i];
				// http://${serverHost}:${serverPort}/org/bytesoft/bytejta/${methodName}/${xid}
				ber.append("/").append(this.serialize(arg));
			}

			ResponseEntity<?> response = restTemplate.postForEntity(ber.toString(), null, returnType, new Object[0]);

			return response.getBody();
		} catch (HttpClientErrorException ex) {
			XAException xaEx = new XAException(XAException.XAER_RMFAIL);
			xaEx.initCause(ex);
			throw xaEx;
		} catch (HttpServerErrorException ex) {
			// int statusCode = ex.getRawStatusCode();
			HttpHeaders headers = ex.getResponseHeaders();
			String failureText = StringUtils.trimToNull(headers.getFirst("failure"));
			String errorText = StringUtils.trimToNull(headers.getFirst("XA_XAER"));

			Boolean failure = failureText == null ? null : Boolean.parseBoolean(failureText);
			Integer errorCode = null;
			try {
				errorCode = errorText == null ? null : Integer.parseInt(errorText);
			} catch (Exception ignore) {
				logger.debug(ignore.getMessage());
			}

			if (failure != null && errorCode != null) {
				XAException xaEx = new XAException(errorCode);
				xaEx.initCause(ex);
				throw xaEx;
			} else {
				XAException xaEx = new XAException(XAException.XAER_RMERR);
				xaEx.initCause(ex);
				throw xaEx;
			}
		} catch (Exception ex) {
			XAException xaEx = new XAException(XAException.XAER_RMERR);
			xaEx.initCause(ex);
			throw xaEx;
		}

	}

	public Object invokeTransactionRecover(Object proxy, Method method, Object[] args) throws Throwable {

		try {
			RestTemplate transactionRestTemplate = SpringCloudBeanRegistry.getInstance().getRestTemplate();
			RestTemplate restTemplate = transactionRestTemplate == null ? new RestTemplate() : transactionRestTemplate;

			RemoteNode remoteNode = CommonUtils.getRemoteNode(this.identifier);
			String contextPathKey = String.format("%s.%s", CONSTANT_CONTENT_PATH, remoteNode.getServiceKey());
			String contextPath = StringUtils.isBlank(remoteNode.getServiceKey()) //
					? null : StringUtils.trimToEmpty(this.environment.getProperty(contextPathKey));

			StringBuilder ber = new StringBuilder();
			ber.append("http://").append(remoteNode.getServerHost()).append(":").append(remoteNode.getServerPort());

			if (StringUtils.isNotBlank(contextPath) || StringUtils.equals(contextPath, "/")) {
				ber.append(contextPath.startsWith("/") ? "" : "/").append(contextPath);
			} // end-if (StringUtils.isNotBlank(contextPath) || StringUtils.equals(contextPath, "/"))

			ber.append("/org/bytesoft/bytejta/");
			ber.append(method.getName());
			for (int i = 0; i < args.length; i++) {
				Serializable arg = (Serializable) args[i];
				ber.append("/").append(this.serialize(arg));
			}

			ResponseEntity<?> response = restTemplate.getForEntity(ber.toString(), TransactionXid[].class, new Object[0]);

			return response.getBody();
		} catch (HttpClientErrorException ex) {
			XAException xaEx = new XAException(XAException.XAER_RMFAIL);
			xaEx.initCause(ex);
			throw xaEx;
		} catch (HttpServerErrorException ex) {
			// int statusCode = ex.getRawStatusCode();
			HttpHeaders headers = ex.getResponseHeaders();
			String failureText = StringUtils.trimToNull(headers.getFirst("failure"));
			String errorText = StringUtils.trimToNull(headers.getFirst("XA_XAER"));

			Boolean failure = failureText == null ? null : Boolean.parseBoolean(failureText);
			Integer errorCode = null;
			try {
				errorCode = errorText == null ? null : Integer.parseInt(errorText);
			} catch (Exception ignore) {
				logger.debug(ignore.getMessage());
			}

			if (failure != null && errorCode != null) {
				XAException xaEx = new XAException(errorCode);
				xaEx.initCause(ex);
				throw xaEx;
			} else {
				XAException xaEx = new XAException(XAException.XAER_RMERR);
				xaEx.initCause(ex);
				throw xaEx;
			}
		} catch (Exception ex) {
			XAException xaEx = new XAException(XAException.XAER_RMERR);
			xaEx.initCause(ex);
			throw xaEx;
		}

	}

	private String serialize(Serializable arg) throws IOException {
		if (Xid.class.isInstance(arg)) {
			Xid xid = (Xid) arg;
			byte[] globalTransactionId = xid.getGlobalTransactionId();
			return ByteUtils.byteArrayToString(globalTransactionId);
		} else if (Integer.class.isInstance(arg) || Integer.TYPE.isInstance(arg)) {
			return String.valueOf(arg);
		} else if (Boolean.class.isInstance(arg) || Boolean.TYPE.isInstance(arg)) {
			return String.valueOf(arg);
		} else {
			byte[] byteArray = SerializeUtils.serializeObject(arg);
			return ByteUtils.byteArrayToString(byteArray);
		}
	}

	public String toString() {
		return String.format("<remote-resource| id= %s>", this.identifier);
	}

	public String getIdentifier() {
		return identifier;
	}

	public void setIdentifier(String identifier) {
		this.identifier = identifier;
	}

	public Environment getEnvironment() {
		return environment;
	}

	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

}
