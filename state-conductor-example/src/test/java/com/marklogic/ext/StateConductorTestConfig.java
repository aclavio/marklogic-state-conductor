package com.marklogic.ext;

import com.marklogic.client.ext.DatabaseClientConfig;
import com.marklogic.client.ext.helper.DatabaseClientProvider;
import com.marklogic.client.ext.spring.SimpleDatabaseClientProvider;
import io.restassured.RestAssured;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/**
 * Defines a test configuration based on sensible defaults for an ml-gradle project.
 */
@Configuration
@PropertySource(value = {"file:gradle.properties", "file:gradle-local.properties"}, ignoreResourceNotFound = true)
public class StateConductorTestConfig implements InitializingBean {

  static Logger logger = LoggerFactory.getLogger(StateConductorTestConfig.class);

	@Value("${mlUsername}")
	private String username;

	@Value("${mlPassword}")
	private String password;

	@Value("${mlHost:localhost}")
	private String host;

	@Value("${mlTestRestPort:0}")
	private Integer restPort;

	/**
	 * Has to be static so that Spring instantiates it first.
	 */
	@Bean
	public static PropertySourcesPlaceholderConfigurer propertyConfigurer() {
		PropertySourcesPlaceholderConfigurer c = new PropertySourcesPlaceholderConfigurer();
		c.setIgnoreResourceNotFound(true);
		return c;
	}

	/**
	 * Defines the configuration details for constructing a DatabaseClient. Assumes the use of digest authentication.
	 * Can subclass and override this method to define a different authentication strategy.
	 *
	 * @return
	 */
	@Bean
	public DatabaseClientConfig databaseClientConfig() {
		return new DatabaseClientConfig(getHost(), getRestPort(), getUsername(), getPassword());
	}

	/**
	 * AbstractSpringMarkLogicTest depends on an instance of DatabaseClientProvider.
	 *
	 * @return
	 */
	@Bean
	public DatabaseClientProvider databaseClientProvider() {
		return new SimpleDatabaseClientProvider(databaseClientConfig());
	}

	@Bean
	public DatabaseClientProvider executionsDatabaseClientProvider() {
	  DatabaseClientConfig config = new DatabaseClientConfig(getHost(), getRestPort(), getUsername(), getPassword());
	  config.setDatabase("state-conductor-executions");
	  return new SimpleDatabaseClientProvider(config);
  }

  /**
   * Called by Spring when the test suite starts up.
   */
  @Override
  public void afterPropertiesSet() {
    RestAssured.baseURI = "http://" + host;
    RestAssured.port = restPort;
    RestAssured.authentication = RestAssured.digest(username, password);
    logger.info("Initialized RestAssured at: " + RestAssured.baseURI + ":" + RestAssured.port);
  }

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	public String getHost() {
		return host;
	}

	public Integer getRestPort() {
		return restPort;
	}
}
