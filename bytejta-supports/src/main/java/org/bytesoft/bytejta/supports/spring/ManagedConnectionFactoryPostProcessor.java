/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytejta.supports.spring;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.Map;

import javax.jms.XAConnectionFactory;
import javax.resource.spi.ManagedConnectionFactory;
import javax.sql.XADataSource;
import javax.transaction.TransactionManager;

import org.apache.commons.dbcp2.managed.BasicManagedDataSource;
import org.bytesoft.bytejta.TransactionBeanFactoryImpl;
import org.bytesoft.bytejta.supports.jdbc.LocalXADataSource;
import org.bytesoft.bytejta.supports.resource.ManagedConnectionFactoryHandler;
import org.bytesoft.bytejta.supports.resource.jdbc.XADataSourceImpl;
import org.bytesoft.transaction.TransactionBeanFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;

public class ManagedConnectionFactoryPostProcessor
		implements BeanPostProcessor, Ordered, SmartInitializingSingleton, ApplicationContextAware {
	static String BEAN_TRANSACTION_MANAGER = "transactionManager";

	private ApplicationContext applicationContext;

	public void afterSingletonsInstantiated() {
		Map<String, LocalXADataSource> beanMap = this.applicationContext.getBeansOfType(LocalXADataSource.class);
		Iterator<Map.Entry<String, LocalXADataSource>> iterator = beanMap == null ? null : beanMap.entrySet().iterator();
		while (iterator != null && iterator.hasNext()) {
			Map.Entry<String, LocalXADataSource> entry = iterator.next();
			LocalXADataSource bean = entry.getValue();
			// 获取所有LocalXADataSource的Bean, 进行初始化, 注入TransactionManager
			this.initializeTransactionManagerIfNecessary(bean);
		}
	}

	private void initializeTransactionManagerIfNecessary(LocalXADataSource target) {
		if (target.getTransactionManager() == null) {
			TransactionManager transactionManager = //
					(TransactionManager) this.applicationContext.getBean(BEAN_TRANSACTION_MANAGER);
			// 为LocalXADataSource注入从Spring容器中获取的TransactionManager事务管理器
			target.setTransactionManager(transactionManager);
		} // end-if (target.getTransactionManager() == null)
	}

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return this.wrapManagedConnectionFactoryIfNecessary(bean, beanName);
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return this.wrapManagedConnectionFactoryIfNecessary(bean, beanName);
	}

	private Object wrapManagedConnectionFactoryIfNecessary(Object bean, String beanName) throws BeansException {
		Class<?> clazz = bean.getClass();
		ClassLoader cl = clazz.getClassLoader();

		Class<?>[] interfaces = clazz.getInterfaces();

		if (this.hasAlreadyBeenWrappedBySelf(bean)) /* managed-connection-factory has already been wrapped */ {
			// 如果已经被wrap包装过了. 就直接返回, 不再wrap包装
			return bean;
		} else if (LocalXADataSource.class.isInstance(bean)) {
			// 对LocalXADataSource的实例Bean进行wrap包装处理
			LocalXADataSource target = (LocalXADataSource) bean;
			this.initializeTransactionManagerIfNecessary(target);
			return bean;
		} else if (BasicManagedDataSource.class.isInstance(bean)) /* spring boot auto configuration */ {
			// 对BasicManagedDataSource的实例Bean进行wrap包装处理
			BasicManagedDataSource managedDataSource = (BasicManagedDataSource) bean;
			TransactionBeanFactory beanFactory = TransactionBeanFactoryImpl.getInstance();
			managedDataSource.setTransactionManager(beanFactory.getTransactionManager());
			return bean;
		} else if (XADataSource.class.isInstance(bean)) {
			// 对XADataSource的实例Bean进行wrap包装处理
			XADataSource xaDataSource = (XADataSource) bean;
			XADataSourceImpl wrappedDataSource = new XADataSourceImpl();
			wrappedDataSource.setIdentifier(beanName);
			wrappedDataSource.setXaDataSource(xaDataSource);
			return wrappedDataSource;
		} else if (XAConnectionFactory.class.isInstance(bean)) {
			// 对XAConnectionFactory的实例Bean进行wrap包装处理, 创建一个动态代理
			ManagedConnectionFactoryHandler interceptor = new ManagedConnectionFactoryHandler(bean);
			interceptor.setIdentifier(beanName);
			return Proxy.newProxyInstance(cl, interfaces, interceptor);
		} else if (ManagedConnectionFactory.class.isInstance(bean)) {
			// 对ManagedConnectionFactory的实例Bean进行wrap包装处理, 创建一个动态代理
			ManagedConnectionFactoryHandler interceptor = new ManagedConnectionFactoryHandler(bean);
			interceptor.setIdentifier(beanName);
			return Proxy.newProxyInstance(cl, interfaces, interceptor);
		} else {
			return bean;
		}
	}

	private boolean hasAlreadyBeenWrappedBySelf(Object bean) {
		if (XADataSourceImpl.class.isInstance(bean)) {
			return true;
		}

		if (Proxy.isProxyClass(bean.getClass()) == false) {
			// 如果不是代理类, 就说明没有被wrap包装过, 需要进行wrap包装
			return false;
		}

		// 如果是代理类, 就获取代理类的原始InvocationHandler
		InvocationHandler handler = Proxy.getInvocationHandler(bean);
		// 判断原始的InvocationHandler是否是ManagedConnectionFactoryHandler的实例对象
		// 如果是, 就说明被wrap包装过, 不需要重复进行wrap包装
		// 如果不是, 就说明没有被wrap包装过, 需要进行wrap包装
		return ManagedConnectionFactoryHandler.class.isInstance(handler);
	}

	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
