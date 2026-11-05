package com.loco.compat;

import com.loco.images.LocalImageStorage;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class HarnessImageTiming {
  @Bean
  static BeanPostProcessor imageTiming() {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof LocalImageStorage)) return bean;
        var proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
            (MethodInterceptor)
                invocation -> {
                  if (!invocation.getMethod().getName().equals("save")) return invocation.proceed();
                  try (var ignored = HarnessTimings.measure("image")) {
                    return invocation.proceed();
                  }
                });
        return proxy.getProxy();
      }
    };
  }
}
