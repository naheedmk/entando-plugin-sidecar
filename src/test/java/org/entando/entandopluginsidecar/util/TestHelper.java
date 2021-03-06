package org.entando.entandopluginsidecar.util;

import static org.entando.entandopluginsidecar.service.ConnectionConfigService.API_VERSION;
import static org.entando.entandopluginsidecar.service.ConnectionConfigService.CONFIG_YAML;

import com.google.common.collect.ImmutableMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.entandopluginsidecar.dto.ConnectionConfigDto;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.plugin.DoneableEntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.model.plugin.EntandoPluginList;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;
import org.springframework.core.io.ClassPathResource;

@UtilityClass
public class TestHelper {

    public static final String CONFIG_ENDPOINT = "/config";
    public static final String RESOURCE = "entando-sidecar";
    public static final String KEYCLOAK_USER = "keycloak-user";
    public static final String WRONG_ROLE = "wrong-role";

    public static final String ENTANDO_PLUGIN_NAME = "testplugin";
    public static final String ENTANDO_API_VERSION = "entando.org/v1";
    public static final String ENTANDO_PLUGIN_CRD = "crd/EntandoPluginCRD.yaml";

    public static void createEntandoPlugin(KubernetesClient client, String pluginName) throws IOException {
        createEntandoPluginWithConfigNames(client, pluginName);
    }

    public static void createEntandoPluginWithConfigNames(KubernetesClient client, String pluginName,
            String... configNames) throws IOException {

        EntandoPlugin entandoPlugin = new EntandoPluginBuilder().withNewSpec()
                .withImage("entando/entando-avatar-plugin")
                .withDbms(DbmsVendor.POSTGRESQL)
                .withReplicas(1)
                .withHealthCheckPath("/management/health")
                .withIngressPath("/dummyPlugin")
                .withSecurityLevel(PluginSecurityLevel.LENIENT)
                .withIngressHostName("dummyPlugin.test")
                .endSpec()
                .build();

        entandoPlugin.setMetadata(new ObjectMetaBuilder().withName(pluginName).build());
        entandoPlugin.setApiVersion(ENTANDO_API_VERSION);
        if (configNames.length > 0) {
            entandoPlugin.getSpec().getConnectionConfigNames().addAll(Arrays.asList(configNames));
        }
        // workaround to make the mock server to work correctly with our custom resource
        KubernetesDeserializer
                .registerCustomKind(entandoPlugin.getApiVersion(), entandoPlugin.getKind(), EntandoPlugin.class);

        CustomResourceDefinition entandoPluginCrd = createEntandoPluginCrd(client);

        client.customResources(entandoPluginCrd, EntandoPlugin.class, EntandoPluginList.class,
                DoneableEntandoPlugin.class)
                .inNamespace(client.getConfiguration().getNamespace())
                .createOrReplace(entandoPlugin);
    }

    public static EntandoPlugin getEntandoPlugin(KubernetesClient client, String entandoPluginName) {
        CustomResourceDefinition definition = getEntandoPluginCrd(client);
        return client
                .customResources(definition, EntandoPlugin.class, EntandoPluginList.class, DoneableEntandoPlugin.class)
                .inNamespace(client.getConfiguration().getNamespace())
                .withName(entandoPluginName).get();
    }

    public static CustomResourceDefinition getEntandoPluginCrd(KubernetesClient client) {
        return client.customResourceDefinitions()
                .withName(EntandoPlugin.CRD_NAME).get();
    }

    public static ConnectionConfigDto getRandomConnectionConfigDto() {
        return ConnectionConfigDto.builder()
                .name(RandomStringUtils.randomAlphabetic(20).toLowerCase())
                .properties(ImmutableMap
                        .of(RandomStringUtils.randomAlphabetic(10), RandomStringUtils.randomAlphabetic(10),
                                RandomStringUtils.randomAlphabetic(10), RandomStringUtils.randomAlphabetic(10)))
                .build();
    }

    public static CustomResourceDefinition createEntandoPluginCrd(KubernetesClient client) throws IOException {
        CustomResourceDefinition entandoPluginCrd = client.customResourceDefinitions().withName(EntandoPlugin.CRD_NAME)
                .get();
        if (entandoPluginCrd == null) {
            List<HasMetadata> list = client.load(new ClassPathResource(ENTANDO_PLUGIN_CRD).getInputStream())
                    .get();
            entandoPluginCrd = (CustomResourceDefinition) list.get(0);
            // see issue https://github.com/fabric8io/kubernetes-client/issues/1486
            entandoPluginCrd.getSpec().getValidation().getOpenAPIV3Schema().setDependencies(null);
            return client.customResourceDefinitions().createOrReplace(entandoPluginCrd);
        }
        return entandoPluginCrd;
    }

    public static void createSecret(KubernetesClient client, ConnectionConfigDto configDto) {
        client.secrets().createNew()
                .withApiVersion(API_VERSION)
                .withNewMetadata().withName(configDto.getName()).endMetadata()
                .withStringData(Collections.singletonMap(CONFIG_YAML, YamlUtils.toYaml(configDto)))
                .done();
    }
}
