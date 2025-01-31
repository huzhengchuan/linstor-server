package com.linbit.linstor.dbdrivers.k8s;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;


/**
 * A client that caches everything.
 * This client assumes that for as long as it is running, it is the *only* client making changes. This is true for
 * the LINSTOR internal resources, so it is safe to use this.
 *
 * @param <T> The Kubernetes resource type.
 * @param <L> The list variant of the Kubernetes resource type.
 * @param <R> The resource operations.
 */
public class K8sCachingClient<T extends HasMetadata, L extends KubernetesResourceList<T>, R extends Resource<T>> implements K8sResourceClient<T>
{
    private final MixedOperation<T, L, R> client;
    private final ConcurrentHashMap<String, T> cache;

    public K8sCachingClient(MixedOperation<T, L, R> clientRef)
    {
        client = clientRef;
        cache = new ConcurrentHashMap<>();
        try
        {
            for (T item : client.list().getItems())
            {
                cache.put(item.getMetadata().getName(), item);
            }
        }
        catch (KubernetesClientException exception)
        {
            // We ignore 404 errors here: This happens if the CRD is not yet applied, which in turn means the cache
            // can be left empty. An actual error will be reported once we actually request something.
            if (exception.getCode() != 404)
            {
                throw exception;
            }
        }
    }

    @Override
    public List<T> list()
    {
        return new ArrayList<>(cache.values());
    }

    @Override
    public T create(T item)
    {
        T updated = client.create(item);
        cache.put(updated.getMetadata().getName(), updated);
        return updated;
    }

    @Override
    public T replace(T item)
    {
        T updated = client.replace(item);
        cache.put(updated.getMetadata().getName(), updated);
        return updated;
    }

    @Override
    public boolean delete()
    {
        boolean deleted = client.delete();
        cache.clear();
        return deleted;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean delete(T item)
    {
        boolean deleted = client.delete(item);
        cache.remove(item.getMetadata().getName());
        return deleted;
    }

    @Override
    public boolean delete(String name)
    {
        boolean deleted = client.withName(name).delete();
        cache.remove(name);
        return deleted;
    }

    @SuppressWarnings("unchecked")
    @Override
    public T createOrReplace(T item)
    {
        T updated = client.createOrReplace(item);
        cache.put(updated.getMetadata().getName(), updated);
        return updated;
    }

    @Override
    public T get(String name)
    {
        return cache.get(name);
    }
}
