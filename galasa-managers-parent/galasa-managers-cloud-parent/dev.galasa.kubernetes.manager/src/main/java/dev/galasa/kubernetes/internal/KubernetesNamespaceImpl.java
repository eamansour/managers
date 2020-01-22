package dev.galasa.kubernetes.internal;

import java.io.IOException;
import java.util.HashMap;

import javax.validation.constraints.NotNull;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.protobuf.Message;

import dev.galasa.framework.spi.IDynamicStatusStoreService;
import dev.galasa.framework.spi.IFramework;
import dev.galasa.kubernetes.IKubernetesNamespace;
import dev.galasa.kubernetes.IResource;
import dev.galasa.kubernetes.KubernetesManagerException;
import dev.galasa.kubernetes.internal.properties.KubernetesStorageClass;
import dev.galasa.kubernetes.internal.resources.ConfigMapImpl;
import dev.galasa.kubernetes.internal.resources.DeploymentImpl;
import dev.galasa.kubernetes.internal.resources.PersistentVolumeClaimImpl;
import dev.galasa.kubernetes.internal.resources.SecretImpl;
import dev.galasa.kubernetes.internal.resources.ServiceImpl;
import dev.galasa.kubernetes.internal.resources.StatefulSetImpl;
import io.kubernetes.client.ProtoClient;
import io.kubernetes.client.ProtoClient.ObjectOrStatus;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1DeleteOptions;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentList;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimList;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimSpec;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceList;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1StatefulSetList;
import io.kubernetes.client.proto.V1.Namespace;
import io.kubernetes.client.util.Yaml;

public class KubernetesNamespaceImpl implements IKubernetesNamespace {

    private final static Log                 logger = LogFactory.getLog(KubernetesNamespaceImpl.class);

    private final KubernetesClusterImpl      cluster;
    private final String                     namespaceId;
    private final IFramework                 framework;
    private final IDynamicStatusStoreService dss;

    public KubernetesNamespaceImpl(KubernetesClusterImpl cluster, String namespaceId, IFramework framework, IDynamicStatusStoreService dss) {
        this.cluster     = cluster;
        this.namespaceId = namespaceId;
        this.framework   = framework;
        this.dss         = dss;
    }

    public String getId() {
        return this.namespaceId;
    }

    public KubernetesClusterImpl getCluster() {
        return this.cluster;
    }

    public void initialiseNamespace() throws KubernetesManagerException {
        ApiClient apiClient = this.cluster.getApi();
        CoreV1Api api = new CoreV1Api(apiClient);

        //*** Create a ConfigMap in the namespace so that we can identify which Run the namespace is for
        HashMap<String, String> data = new HashMap<>();
        data.put("galasaRun", this.framework.getTestRunName());

        V1ConfigMap configMap = new V1ConfigMap();
        configMap.setApiVersion("v1");
        configMap.setKind("ConfigMap");
        configMap.setData(data);

        V1ObjectMeta metadata = new V1ObjectMeta();
        configMap.setMetadata(metadata);
        metadata.setName("galasa");

        try {
            api.createNamespacedConfigMap(this.namespaceId, configMap, null, null, null);
        } catch(ApiException e) {         
            if (e.getCode() == 409) {
                throw new KubernetesManagerException("The allocated namespace " + this.namespaceId + " on cluster " + this.cluster.getId() + " is dirty, the configmap galasa still exists", e);
            }
            throw new KubernetesManagerException("Unable to initialise the namespace with a configmap", e);
        }
    }








    public void discard(String runName) throws KubernetesManagerException {
        cleanNamespace();
        clearSlot(runName);
    }

    private void clearSlot(String runName) {
        try {
            String namespacePrefix = "cluster." + this.cluster.getId() + ".namespace." + this.namespaceId;
            String slotKey = "slot.run." + runName + ".cluster." + this.cluster.getId() + ".namespace." + namespaceId;

            String slotStatus = dss.get(slotKey);
            if ("active".equals(slotStatus)) {
                //*** Decrement the cluster current slot count and mark the namespace as free
                while(true) { //*** have to loop around incase another test changed the current slot count
                    int currentSlots = 0;
                    String sCurrentSlots = dss.get("cluster." + this.cluster.getId() + ".current.slots");
                    if (sCurrentSlots != null) {
                        currentSlots = Integer.parseInt(sCurrentSlots);
                    }
                    currentSlots--;

                    if (currentSlots < 0) {
                        currentSlots = 0;
                    }

                    HashMap<String, String> slotOtherValues = new HashMap<>();
                    slotOtherValues.put(namespacePrefix, "free");
                    slotOtherValues.put(slotKey, "free");
                    if (dss.putSwap("cluster." + this.cluster.getId() + ".current.slots", sCurrentSlots, Integer.toString(currentSlots), slotOtherValues)) {
                        break;
                    }
                }
            }

            //*** Slot count has been decremented, we can now delete the actual DSS properties
            dss.deletePrefix(namespacePrefix);
            dss.deletePrefix(slotKey);
        } catch(Exception e) {
            logger.error("Problem discarding the namespace",e);
        }
    }

    private void cleanNamespace() throws KubernetesManagerException {
        CoreV1Api coreApi = new CoreV1Api(this.cluster.getApi());
        AppsV1Api appsApi = new AppsV1Api(this.cluster.getApi());
        ProtoClient pc = new ProtoClient(this.cluster.getApi());

        try {
            //*** Delete all configmaps that exist in the namespace
            V1ConfigMapList configMapList = coreApi.listNamespacedConfigMap(this.namespaceId, null, null, null, null, null, null, null, null, null);

            for(V1ConfigMap configMap : configMapList.getItems()) {
                logger.debug("Deleting ConfigMap " + this.cluster.getId() + "/" + this.namespaceId + "/" + configMap.getMetadata().getName());
                V1DeleteOptions options = new V1DeleteOptions();
                options.setGracePeriodSeconds(0L);
                coreApi.deleteNamespacedConfigMap(configMap.getMetadata().getName(), this.namespaceId, null, null, 0, null, null, null);
            }

            //*** Delete all secrets that exist in the namespace
            V1SecretList secretList = coreApi.listNamespacedSecret(this.namespaceId, null, null, null, null, null, null, null, null, null);

            for(V1Secret secret : secretList.getItems()) {
                // Check the secret is not for a service account

                V1ObjectMeta metadata = secret.getMetadata();
                if (metadata != null && metadata.getAnnotations() != null) {
                    if (metadata.getAnnotations().containsKey("kubernetes.io/service-account.name")) {
                        continue;
                    }
                }

                logger.debug("Deleting Secret " + this.cluster.getId() + "/" + this.namespaceId + "/" + secret.getMetadata().getName());
                V1DeleteOptions options = new V1DeleteOptions();
                options.setGracePeriodSeconds(0L);
                coreApi.deleteNamespacedSecret(secret.getMetadata().getName(), this.namespaceId, null, null, 0, null, null, null);
            }

            //*** Delete all Deployments that exist in the namespace
            V1DeploymentList deploymentList = appsApi.listNamespacedDeployment(this.namespaceId, null, null, null, null, null, null, null, null, null);

            for(V1Deployment deployment : deploymentList.getItems()) {
                logger.debug("Deleting Deployment " + this.cluster.getId() + "/" + this.namespaceId + "/" + deployment.getMetadata().getName());
                V1DeleteOptions options = new V1DeleteOptions();
                options.setGracePeriodSeconds(0L);
                appsApi.deleteNamespacedDeployment(deployment.getMetadata().getName(), this.namespaceId, null, null, 0, null, null, null);
            }

            //*** Delete all StatefulSets that exist in the namespace
            V1StatefulSetList statefulsetList = appsApi.listNamespacedStatefulSet(this.namespaceId, null, null, null, null, null, null, null, null, null);

            for(V1StatefulSet statefulset : statefulsetList.getItems()) {
                logger.debug("Deleting StatefulSet " + this.cluster.getId() + "/" + this.namespaceId + "/" + statefulset.getMetadata().getName());
                V1DeleteOptions options = new V1DeleteOptions();
                options.setGracePeriodSeconds(0L);
                appsApi.deleteNamespacedStatefulSet(statefulset.getMetadata().getName(), this.namespaceId, null, null, 0, null, null, null);
            }

            //*** Delete all Services that exist in the namespace
            V1ServiceList serviceList = coreApi.listNamespacedService(this.namespaceId, null, null, null, null, null, null, null, null, null);

            for(V1Service service : serviceList.getItems()) {
                logger.debug("Deleting Service " + this.cluster.getId() + "/" + this.namespaceId + "/" + service.getMetadata().getName());
                V1DeleteOptions options = new V1DeleteOptions();
                options.setGracePeriodSeconds(0L);
                coreApi.deleteNamespacedService(service.getMetadata().getName(), this.namespaceId, null, null, 0, null, null, null);
            }

            //*** Delete all PVCs that exist in the namespace
            V1PersistentVolumeClaimList pvcList = coreApi.listNamespacedPersistentVolumeClaim(this.namespaceId, null, null, null, null, null, null, null, null, null);

            for(V1PersistentVolumeClaim pvc : pvcList.getItems()) {
                logger.debug("Deleting PVC " + this.cluster.getId() + "/" + this.namespaceId + "/" + pvc.getMetadata().getName());
                //TODO raise issue because the delete pvc api call fails
                
//                V1DeleteOptions options = new V1DeleteOptions();
//                options.setGracePeriodSeconds(0L);
//                coreApi.deleteNamespacedPersistentVolumeClaim(pvc.getMetadata().getName(), this.namespaceId, null, null, 0, null, null, null);
                ObjectOrStatus<Message> response = pc.delete(Namespace.newBuilder(), "/api/v1/namespaces/" + this.namespaceId + "/persistentvolumeclaims/" + pvc.getMetadata().getName());
                if (response.status != null) {
                    throw new KubernetesManagerException("Failed to delete PVC:-\n" + response.status.toString());
                }
            }

        } catch(Exception e) {
            throw new KubernetesManagerException("Problem trying to delete all the resources in the namespace", e);
        }
    }

    public static void deleteDss(String runName, String clusterId, String namespaceId, IDynamicStatusStoreService dss, IFramework framework) throws KubernetesManagerException {
        KubernetesClusterImpl cluster = new KubernetesClusterImpl(clusterId, dss, framework);
        KubernetesNamespaceImpl namespace = new KubernetesNamespaceImpl(cluster, namespaceId, framework, dss);

        namespace.discard(runName);
    }

    @Override
    @NotNull
    public IResource createResource(@NotNull String yaml) throws KubernetesManagerException {

        if (yaml == null || yaml.trim().isEmpty()) {
            throw new KubernetesManagerException("Missing YAML");
        }

        Object oResource = null;
        try {
            oResource = Yaml.load(yaml);
        } catch (IOException e) {
            throw new KubernetesManagerException("Unable to convert resource YAML to a Kubernetes resource", e);
        }

        try {
            if (oResource instanceof V1ConfigMap) {
                return createConfigMap((V1ConfigMap) oResource);
            } else if (oResource instanceof V1PersistentVolumeClaim) {
                return createPersistentVolumeClaim((V1PersistentVolumeClaim) oResource);
            } else if (oResource instanceof V1Secret) {
                return createSecret((V1Secret) oResource);
            } else if (oResource instanceof V1Deployment) {
                return createDeployment((V1Deployment) oResource);
            } else if (oResource instanceof V1StatefulSet) {
                return createStatefulSet((V1StatefulSet) oResource);
            } else if (oResource instanceof V1Service) {
                return createService((V1Service) oResource);
            } else {
                throw new KubernetesManagerException("The Kubernetes Manager does not at present support resource type " + oResource.getClass().getSimpleName());
            }
        } catch(ApiException e) {
            throw new KubernetesManagerException("Unable to create resource:-" + e.getResponseBody(), e);
        }
    }

    private @NotNull IResource createPersistentVolumeClaim(@NotNull V1PersistentVolumeClaim persistentVolumeClaim) throws KubernetesManagerException, ApiException {
        CoreV1Api api = new CoreV1Api(cluster.getApi());
        
        String storageClass = KubernetesStorageClass.get(this.cluster);
        if (storageClass != null) {
            V1PersistentVolumeClaimSpec spec = persistentVolumeClaim.getSpec();
            if (spec == null) {
                spec = new V1PersistentVolumeClaimSpec();
                persistentVolumeClaim.setSpec(spec);
            }
            
            spec.setStorageClassName(storageClass);
        }
                       
        System.out.println(Yaml.dump(persistentVolumeClaim));
        
        V1PersistentVolumeClaim actualPvc = api.createNamespacedPersistentVolumeClaim(this.namespaceId, persistentVolumeClaim, null, null, null);
        
        logger.debug("PersistentVolumeClaim " + actualPvc.getMetadata().getName() + " created in namespace " + this.namespaceId + " on cluster " + this.cluster.getId());

        return new PersistentVolumeClaimImpl(this, actualPvc);
    }

    private @NotNull IResource createConfigMap(@NotNull V1ConfigMap configMap) throws KubernetesManagerException, ApiException {
        CoreV1Api api = new CoreV1Api(cluster.getApi());
        V1ConfigMap actualConfig = api.createNamespacedConfigMap(namespaceId, configMap, null, null, null);

        logger.debug("ConfigMap " + actualConfig.getMetadata().getName() + " created in namespace " + this.namespaceId + " on cluster " + this.cluster.getId());

        return new ConfigMapImpl(this, actualConfig);
    }

    private @NotNull IResource createSecret(@NotNull V1Secret secret) throws KubernetesManagerException, ApiException {
        CoreV1Api api = new CoreV1Api(cluster.getApi());
        V1Secret actualSecret = api.createNamespacedSecret(namespaceId, secret, null, null, null);

        logger.debug("Secret " + actualSecret.getMetadata().getName() + " created in namespace " + this.namespaceId + " on cluster " + this.cluster.getId());

        return new SecretImpl(this, actualSecret);
    }

    private @NotNull IResource createService(@NotNull V1Service service) throws KubernetesManagerException, ApiException {
        CoreV1Api api = new CoreV1Api(cluster.getApi());
        V1Service actualService = api.createNamespacedService(namespaceId, service, null, null, null);

        logger.debug("Service " + actualService.getMetadata().getName() + " created in namespace " + this.namespaceId + " on cluster " + this.cluster.getId());

        return new ServiceImpl(this, actualService);
    }

    private @NotNull IResource createDeployment(@NotNull V1Deployment deployment) throws KubernetesManagerException, ApiException {
        AppsV1Api api = new AppsV1Api(cluster.getApi());
        V1Deployment actualDeployment = api.createNamespacedDeployment(namespaceId, deployment, null, null, null);

        logger.debug("Deployment " + actualDeployment.getMetadata().getName() + " created in namespace " + this.namespaceId + " on cluster " + this.cluster.getId());

        return new DeploymentImpl(this, actualDeployment);
    }

    private @NotNull IResource createStatefulSet(@NotNull V1StatefulSet statefulSet) throws KubernetesManagerException, ApiException {
        AppsV1Api api = new AppsV1Api(cluster.getApi());
        V1StatefulSet actualStatefulSet = api.createNamespacedStatefulSet(namespaceId, statefulSet, null, null, null);

        logger.debug("StatefulSet " + actualStatefulSet.getMetadata().getName() + " created in namespace " + this.namespaceId + " on cluster " + this.cluster.getId());

        return new StatefulSetImpl(this, actualStatefulSet);
    }

}