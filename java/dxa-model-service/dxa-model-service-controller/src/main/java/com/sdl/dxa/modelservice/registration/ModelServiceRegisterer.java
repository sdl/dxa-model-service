package com.sdl.dxa.modelservice.registration;

import com.sdl.delivery.configuration.Configuration;
import com.sdl.delivery.configuration.ConfigurationException;
import com.sdl.delivery.configuration.XPathConfigurationPath;
import com.sdl.delivery.configuration.xml.XMLConfigurationReaderImpl;
import com.sdl.odata.client.BasicODataClientQuery;
import com.sdl.web.discovery.datalayer.model.ContentServiceCapability;
import com.sdl.web.discovery.datalayer.model.Environment;
import com.sdl.web.discovery.datalayer.model.KeyValuePair;
import com.sdl.web.discovery.registration.ODataClientProvider;
import com.sdl.web.discovery.registration.SecuredODataClient;
import com.sdl.web.discovery.registration.capability.ContentServiceCapabilityBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This bean reads a configuration and tries to register the service as an extension property of ContentServiceCapability.
 */
@Slf4j
@Service
@Conditional(AutoRegistrationCondition.class)
public class ModelServiceRegisterer {

    static final String CONFIG_FILE_NAME = "cd_storage_conf.xml";

    private static final XPathConfigurationPath CONTENT_SERVICE_CAPABILITY_ROLE_XPATH =
            new XPathConfigurationPath("/Roles/Role[@Name=\"ContentServiceCapability\"]");

    private final SecuredODataClient dataClient;
    private boolean registered = false;
    private int attempts = 5;

    private final Configuration configuration;

    private boolean isRecordValid() throws ConfigurationException {
        Optional<KeyValuePair> storedProp = getPropertyToCheck(loadStoredCapability().getExtensionProperties());
        Optional<KeyValuePair> ownProp = getPropertyToCheck(loadNewCapability(getRoleConfiguration(), loadEnvironment()).getExtensionProperties());

        return storedProp.isPresent() && ownProp.isPresent() && storedProp.get().getValue().equals(ownProp.get().getValue());
    }

    public ModelServiceRegisterer() throws ConfigurationException {
        configuration = readConfiguration();
        dataClient = new ODataClientProvider(configuration).provideClient();
    }

    public static void main(String[] args) throws ConfigurationException, InterruptedException {
        new ModelServiceRegisterer().doRegistration();
    }

    private Configuration readConfiguration() throws ConfigurationException {
        return new XMLConfigurationReaderImpl().readConfiguration(CONFIG_FILE_NAME).getConfiguration("ConfigRepository");
    }

    private Optional<KeyValuePair> getPropertyToCheck(List<KeyValuePair> extensionProperties) throws ConfigurationException {

        return extensionProperties
                .stream()
                .filter(pair -> pair.getKey().equals("dxa-model-service"))
                .findFirst();
    }

    private Configuration getRoleConfiguration() throws ConfigurationException {
        return this.configuration.getConfiguration(CONTENT_SERVICE_CAPABILITY_ROLE_XPATH);
    }
    @PostConstruct

    // Do the registration over every 10 minutes
    // If it can't register due to some reason try 10 times and then fail
    @Scheduled(fixedDelay=600000)
    public void doRegistration() throws ConfigurationException, InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        new Thread(() -> {
            while (!registered && attempts > 0) {
                try {
                    attempts--;
                    register();
                } catch (ConfigurationException e) {
                    log.error("Could not register Model Service {}", e.getMessage());
                }
            }

            // Reset
            attempts = 10;
            registered = false;
            latch.countDown();
        }).start();

        latch.await();
    }

    @PostConstruct
    private void register() throws ConfigurationException {
        log.debug("Automatically registering of a Model Service in Content Service Capability");

        Environment environment = loadEnvironment();
        ContentServiceCapability storedCapability = loadStoredCapability();
        log.trace("Loaded an existing capability {}", storedCapability);

        ContentServiceCapability newCapability = loadNewCapability(this.getRoleConfiguration(), environment);
        log.trace("Loaded a capability from local configuration file {}, {}", CONFIG_FILE_NAME, newCapability);

        KeyValuePair registeredFrom = new KeyValuePair();
        registeredFrom.setKey("last-registered-by");
        registeredFrom.setValue(System.getProperty("user.name"));

        newCapability.getExtensionProperties().add(registeredFrom);

        List<KeyValuePair> mergedProperties = mergeExtensionProperties(newCapability, storedCapability);
        log.debug("Merged properties: {}", mergedProperties);

        storedCapability.setExtensionProperties(mergedProperties);
        storedCapability.setEnvironment(environment);
        dataClient.updateEntity(storedCapability);

        if(isRecordValid()) {
            this.registered = true;
            log.info("Model Service capability {} has been registered by user {}", newCapability, registeredFrom);
        } else {
            this.registered = false;
        }
    }

    private Environment loadEnvironment() {
        Environment environment = new Environment();
        environment.setId("DefaultEnvironment");
        return environment;
    }

    private ContentServiceCapability loadStoredCapability() throws ConfigurationException {
        return dataClient.<ContentServiceCapability>getEntities(new BasicODataClientQuery.Builder().withEntityType(ContentServiceCapability.class).build())
                .stream()
                .filter(ContentServiceCapability.class::isInstance)
                .map(ContentServiceCapability.class::cast)
                .findFirst()
                .orElseThrow(() -> new ConfigurationException("Cannot load ContentServiceCapability, so cannot register a Model Service"));
    }

    private ContentServiceCapability loadNewCapability(Configuration role, Environment environment) throws ConfigurationException {
        return (ContentServiceCapability) new ContentServiceCapabilityBuilder().buildCapability(role, environment);
    }

    private List<KeyValuePair> mergeExtensionProperties(ContentServiceCapability newCapability, ContentServiceCapability storedCapability) {
        List<KeyValuePair> newProperties = newCapability.getExtensionProperties();

        Predicate<KeyValuePair> notInNewProperties = knownProp -> newProperties.stream()
                .noneMatch(newProp -> Objects.equals(knownProp.getKey(), newProp.getKey()));

        List<KeyValuePair> knownProperties = storedCapability.getExtensionProperties().stream()
                .filter(notInNewProperties)
                .peek(pair -> log.debug("Property {} is not in MS config, so just leave it as is", pair))
                .collect(Collectors.toList());

        return Stream.of(newProperties, knownProperties).flatMap(Collection::stream).collect(Collectors.toList());
    }
}
