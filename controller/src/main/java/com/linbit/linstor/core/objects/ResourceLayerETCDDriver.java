package com.linbit.linstor.core.objects;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.SingleColumnDatabaseDriver;
import com.linbit.linstor.core.identifier.NodeName;
import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.core.objects.ResourceLayerIdGenericDbDriver.RscLayerInfoData;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.dbdrivers.GeneratedDatabaseTables;
import com.linbit.linstor.dbdrivers.etcd.BaseEtcdDriver;
import com.linbit.linstor.dbdrivers.etcd.EtcdUtils;
import com.linbit.linstor.dbdrivers.interfaces.ResourceLayerIdDatabaseDriver;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.storage.AbsRscData;
import com.linbit.linstor.storage.interfaces.categories.resource.RscLayerObject;
import com.linbit.linstor.storage.interfaces.categories.resource.VlmProviderObject;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;
import com.linbit.linstor.transaction.TransactionMgrETCD;

import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.LayerResourceIds.LAYER_RESOURCE_ID;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.LayerResourceIds.LAYER_RESOURCE_KIND;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.LayerResourceIds.LAYER_RESOURCE_PARENT_ID;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.LayerResourceIds.LAYER_RESOURCE_SUFFIX;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.LayerResourceIds.NODE_NAME;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.LayerResourceIds.RESOURCE_NAME;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Singleton
public class ResourceLayerETCDDriver extends BaseEtcdDriver implements ResourceLayerIdDatabaseDriver
{
    private final ErrorReporter errorReporter;
    private final SingleColumnDatabaseDriver<AbsRscData<VlmProviderObject>, RscLayerObject> parentDriver;

    @Inject
    public ResourceLayerETCDDriver(ErrorReporter errorReporterRef, Provider<TransactionMgrETCD> transMgrProviderRef)
    {
        super(transMgrProviderRef);
        errorReporter = errorReporterRef;

        parentDriver = (rscData, newParentData) ->
        {
            RscLayerObject oldParentData = rscData.getParent();
            errorReporter.logTrace(
                "Updating %s's parent resource id from [%d] to [%d] %s",
                rscData.getClass().getSimpleName(),
                oldParentData == null ? null : oldParentData.getRscLayerId(),
                newParentData == null ? null : newParentData.getRscLayerId(),
                getId(rscData)
            );

            if (newParentData == null)
            {
                namespace(EtcdUtils.buildKey(LAYER_RESOURCE_PARENT_ID, Integer.toString(rscData.getRscLayerId())))
                    .delete(false);
            }
            else
            {
                namespace(GeneratedDatabaseTables.LAYER_RESOURCE_IDS, Integer.toString(rscData.getRscLayerId()))
                    .put(LAYER_RESOURCE_PARENT_ID, Integer.toString(rscData.getParent().getRscLayerId()));
            }

        };
    }

    @Override
    public List<? extends RscLayerInfo> loadAllResourceIds() throws DatabaseException
    {
        List<RscLayerInfoData> ret = new ArrayList<>();

        Map<String, String> allIds = namespace(GeneratedDatabaseTables.LAYER_RESOURCE_IDS).get(true);
        Set<String> pks = EtcdUtils.getComposedPkList(allIds);
        try
        {
            for (String pk : pks)
            {
                String parentIdStr = allIds.get(EtcdUtils.buildKey(LAYER_RESOURCE_PARENT_ID, pk));

                ret.add(
                    new RscLayerInfoData(
                        new NodeName(allIds.get(EtcdUtils.buildKey(NODE_NAME, pk))),
                        new ResourceName(allIds.get(EtcdUtils.buildKey(RESOURCE_NAME, pk))),
                        Integer.parseInt(allIds.get(EtcdUtils.buildKey(LAYER_RESOURCE_ID, pk))),
                        parentIdStr != null && !parentIdStr.isEmpty() ? Integer.parseInt(parentIdStr) : null,
                        DeviceLayerKind.valueOf(allIds.get(EtcdUtils.buildKey(LAYER_RESOURCE_KIND, pk))),
                        allIds.get(EtcdUtils.buildKey(LAYER_RESOURCE_SUFFIX, pk))
                    )
                );
            }
        }
        catch (InvalidNameException exc)
        {
            throw new ImplementationError("Unrestorable name loaded from the database", exc);
        }
        return ret;
    }

    @Override
    public void persist(RscLayerObject rscData) throws DatabaseException
    {
        errorReporter.logTrace("Creating LayerResourceId %s", getId(rscData));
        FluentLinstorTransaction namespace = namespace(
            GeneratedDatabaseTables.LAYER_RESOURCE_IDS, Integer.toString(rscData.getRscLayerId())
        );
        namespace
            .put(NODE_NAME, rscData.getResource().getAssignedNode().getName().value)
            .put(RESOURCE_NAME, rscData.getResourceName().value)
            .put(LAYER_RESOURCE_KIND, rscData.getLayerKind().name())
            .put(LAYER_RESOURCE_SUFFIX, rscData.getResourceNameSuffix());
        if (rscData.getParent() != null)
        {
            namespace.put(LAYER_RESOURCE_PARENT_ID, Integer.toString(rscData.getParent().getRscLayerId()));
        }
    }

    @Override
    public void delete(RscLayerObject rscData) throws DatabaseException
    {
        namespace(GeneratedDatabaseTables.LAYER_RESOURCE_IDS, Integer.toString(rscData.getRscLayerId()))
            .delete(true);
    }

    @Override
    public <T extends VlmProviderObject> SingleColumnDatabaseDriver<AbsRscData<T>, RscLayerObject> getParentDriver()
    {
        // sorry for this dirty hack :(

        // Java does not allow to cast <?> to <T> for good reasons, but here those reasons are irrelevant as the
        // SingleColumnDatatbaseDriver does not use anything of that T. The reason it still needs to be declared as T
        // is the usage of the implementation of the layer-specific resource data.
        return (SingleColumnDatabaseDriver<AbsRscData<T>, RscLayerObject>) ((Object) parentDriver);
    }

    public static String getId(RscLayerObject rscData)
    {
        return rscData.getLayerKind().name() +
            " (id: " + rscData.getRscLayerId() +
            ", rscName: " + rscData.getSuffixedResourceName() +
            ", parent: " + (rscData.getParent() == null ? "-" : rscData.getParent().getRscLayerId()) + ")";
    }

}
