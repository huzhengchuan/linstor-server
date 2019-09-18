package com.linbit.linstor.core.objects;

import com.linbit.CollectionDatabaseDriver;
import com.linbit.ImplementationError;
import com.linbit.InvalidIpAddressException;
import com.linbit.InvalidNameException;
import com.linbit.SingleColumnDatabaseDriver;
import com.linbit.ValueOutOfRangeException;
import com.linbit.linstor.annotation.SystemContext;
import com.linbit.linstor.core.identifier.ResourceGroupName;
import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.core.identifier.VolumeNumber;
import com.linbit.linstor.core.objects.ResourceGroup.InitMaps;
import com.linbit.linstor.dbdrivers.AbsDatabaseDriver;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.dbdrivers.DatabaseLoader;
import com.linbit.linstor.dbdrivers.DbEngine;
import com.linbit.linstor.dbdrivers.GeneratedDatabaseTables;
import com.linbit.linstor.dbdrivers.interfaces.ResourceGroupDatabaseDriver;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.propscon.PropsContainerFactory;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.ObjectProtection;
import com.linbit.linstor.security.ObjectProtectionDatabaseDriver;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;
import com.linbit.linstor.storage.kinds.DeviceProviderKind;
import com.linbit.linstor.transaction.TransactionMgr;
import com.linbit.linstor.transaction.TransactionObjectFactory;
import com.linbit.utils.Pair;

import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.ALLOWED_PROVIDER_LIST;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.DESCRIPTION;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.DISKLESS_ON_REMAINING;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.DO_NOT_PLACE_WITH_RSC_LIST;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.DO_NOT_PLACE_WITH_RSC_REGEX;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.LAYER_STACK;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.POOL_NAME;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.REPLICAS_ON_DIFFERENT;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.REPLICAS_ON_SAME;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.REPLICA_COUNT;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.RESOURCE_GROUP_DSP_NAME;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.RESOURCE_GROUP_NAME;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.ResourceGroups.UUID;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

@Singleton
public class ResourceGroupDbDriver
    extends AbsDatabaseDriver<ResourceGroup, ResourceGroup.InitMaps, Void>
    implements ResourceGroupDatabaseDriver
{

    private final PropsContainerFactory propsContainerFactory;
    private final TransactionObjectFactory transObjFactory;

    private final SingleColumnDatabaseDriver<ResourceGroup, String> descriptionDriver;
    private final CollectionDatabaseDriver<ResourceGroup, DeviceLayerKind> layerStackDriver;
    private final SingleColumnDatabaseDriver<ResourceGroup, Integer> replicaCountDriver;
    private final SingleColumnDatabaseDriver<ResourceGroup, String> storPoolNameDriver;
    private final CollectionDatabaseDriver<ResourceGroup, String> doNotPlaceWithRscListDriver;
    private final SingleColumnDatabaseDriver<ResourceGroup, String> doNotPlaceWithRscRegexDriver;
    private final CollectionDatabaseDriver<ResourceGroup, String> replicasOnSameListDriver;
    private final CollectionDatabaseDriver<ResourceGroup, String> replicasOnDifferentListDriver;
    private final CollectionDatabaseDriver<ResourceGroup, DeviceProviderKind> allowedProviderListDriver;
    private final SingleColumnDatabaseDriver<ResourceGroup, Boolean> disklessOnRemainingDriver;
    private final Provider<TransactionMgr> transMgrProvider;

    @Inject
    public ResourceGroupDbDriver(
        @SystemContext AccessContext dbCtxRef,
        ErrorReporter errorReporterRef,
        DbEngine dbEngine,
        ObjectProtectionDatabaseDriver objProtDriverRef,
        PropsContainerFactory propsContainerFactoryRef,
        TransactionObjectFactory transObjFactoryRef,
        Provider<TransactionMgr> transMgrProviderRef
    )
    {
        super(
            errorReporterRef,
            GeneratedDatabaseTables.RESOURCE_GROUPS,
            dbEngine,
            objProtDriverRef
        );
        propsContainerFactory = propsContainerFactoryRef;
        transObjFactory = transObjFactoryRef;
        transMgrProvider = transMgrProviderRef;

        setColumnSetter(UUID, rscGrp -> rscGrp.getUuid().toString());
        setColumnSetter(RESOURCE_GROUP_NAME, rscGrp -> rscGrp.getName().value);
        setColumnSetter(RESOURCE_GROUP_DSP_NAME, rscGrp -> rscGrp.getName().displayValue);
        setColumnSetter(DESCRIPTION, rscGrp -> rscGrp.getDescription(dbCtxRef));
        setColumnSetter(
            LAYER_STACK,
            rscGrp -> toString(rscGrp.getAutoPlaceConfig().getLayerStackList(dbCtxRef))
        );
        setColumnSetter(REPLICA_COUNT, rscGrp -> rscGrp.getAutoPlaceConfig().getReplicaCount(dbCtxRef));
        setColumnSetter(POOL_NAME, rscGrp -> rscGrp.getAutoPlaceConfig().getStorPoolNameStr(dbCtxRef));
        setColumnSetter(
            DO_NOT_PLACE_WITH_RSC_REGEX,
            rscGrp -> rscGrp.getAutoPlaceConfig().getDoNotPlaceWithRscRegex(dbCtxRef)
        );
        setColumnSetter(
            DO_NOT_PLACE_WITH_RSC_LIST,
            rscGrp -> toString(rscGrp.getAutoPlaceConfig().getDoNotPlaceWithRscList(dbCtxRef))
        );
        setColumnSetter(
            ALLOWED_PROVIDER_LIST,
            rscGrp -> toString(rscGrp.getAutoPlaceConfig().getProviderList(dbCtxRef))
        );
        setColumnSetter(
            DISKLESS_ON_REMAINING,
            rscGrp -> rscGrp.getAutoPlaceConfig().getDisklessOnRemaining(dbCtxRef)
        );

        switch (getDbType())
        {
            case ETCD:
                setColumnSetter(
                    REPLICAS_ON_SAME,
                    rscGrp -> toString(rscGrp.getAutoPlaceConfig().getReplicasOnSameList(dbCtxRef))
                );
                setColumnSetter(
                    REPLICAS_ON_DIFFERENT,
                    rscGrp -> toString(rscGrp.getAutoPlaceConfig().getReplicasOnDifferentList(dbCtxRef))
                );
                break;
            case SQL:
                setColumnSetter(
                    REPLICAS_ON_SAME,
                    rscGrp -> toBlob(rscGrp.getAutoPlaceConfig().getReplicasOnSameList(dbCtxRef))
                );
                setColumnSetter(
                    REPLICAS_ON_DIFFERENT,
                    rscGrp -> toBlob(rscGrp.getAutoPlaceConfig().getReplicasOnDifferentList(dbCtxRef))
                );
                break;
            default:
                throw new ImplementationError("Unknown database type: " + getDbType());
        }

        descriptionDriver = generateSingleColumnDriver(
            DESCRIPTION,
            rscGrp -> rscGrp.getDescription(dbCtxRef),
            Function.identity()
        );
        layerStackDriver = generateCollectionToJsonStringArrayDriver(LAYER_STACK);
        replicaCountDriver = generateSingleColumnDriver(
            REPLICA_COUNT,
            rscGrp -> Objects.toString(rscGrp.getAutoPlaceConfig().getReplicaCount(dbCtxRef)),
            Function.identity()
        );
        storPoolNameDriver = generateSingleColumnDriver(
            POOL_NAME,
            rscGrp -> Objects.toString(rscGrp.getAutoPlaceConfig().getStorPoolNameStr(dbCtxRef)),
            Function.identity()
        );
        doNotPlaceWithRscListDriver = generateCollectionToJsonStringArrayDriver(DO_NOT_PLACE_WITH_RSC_LIST);
        doNotPlaceWithRscRegexDriver = generateSingleColumnDriver(
            DO_NOT_PLACE_WITH_RSC_REGEX,
            rscGrp -> Objects.toString(rscGrp.getAutoPlaceConfig().getDoNotPlaceWithRscRegex(dbCtxRef)),
            Function.identity()
        );
        replicasOnSameListDriver = generateCollectionToJsonStringArrayDriver(REPLICAS_ON_SAME);
        replicasOnDifferentListDriver = generateCollectionToJsonStringArrayDriver(REPLICAS_ON_DIFFERENT);
        allowedProviderListDriver = generateCollectionToJsonStringArrayDriver(ALLOWED_PROVIDER_LIST);
        disklessOnRemainingDriver = generateSingleColumnDriver(
            DISKLESS_ON_REMAINING,
            rscGrp -> Objects.toString(rscGrp.getAutoPlaceConfig().getDisklessOnRemaining(dbCtxRef)),
            Function.identity()
        );
    }

    @Override
    public SingleColumnDatabaseDriver<ResourceGroup, String> getDescriptionDriver()
    {
        return descriptionDriver;
    }

    @Override
    public CollectionDatabaseDriver<ResourceGroup, DeviceLayerKind> getLayerStackDriver()
    {
        return layerStackDriver;
    }

    @Override
    public SingleColumnDatabaseDriver<ResourceGroup, Integer> getReplicaCountDriver()
    {
        return replicaCountDriver;
    }

    @Override
    public SingleColumnDatabaseDriver<ResourceGroup, String> getStorPoolNameDriver()
    {
        return storPoolNameDriver;
    }

    @Override
    public CollectionDatabaseDriver<ResourceGroup, String> getDoNotPlaceWithRscListDriver()
    {
        return doNotPlaceWithRscListDriver;
    }

    @Override
    public SingleColumnDatabaseDriver<ResourceGroup, String> getDoNotPlaceWithRscRegexDriver()
    {
        return doNotPlaceWithRscRegexDriver;
    }

    @Override
    public CollectionDatabaseDriver<ResourceGroup, String> getReplicasOnSameListDriver()
    {
        return replicasOnSameListDriver;
    }

    @Override
    public CollectionDatabaseDriver<ResourceGroup, String> getReplicasOnDifferentDriver()
    {
        return replicasOnDifferentListDriver;
    }

    @Override
    public CollectionDatabaseDriver<ResourceGroup, DeviceProviderKind> getAllowedProviderListDriver()
    {
        return allowedProviderListDriver;
    }

    @Override
    public SingleColumnDatabaseDriver<ResourceGroup, Boolean> getDisklessOnRemainingDriver()
    {
        return disklessOnRemainingDriver;
    }

    @Override
    protected Pair<ResourceGroup, ResourceGroup.InitMaps> load(
        RawParameters raw,
        Void loadAllDataRef
    )
        throws DatabaseException, InvalidNameException, InvalidIpAddressException, ValueOutOfRangeException
    {
        ResourceGroupName rscGrpName = raw.build(RESOURCE_GROUP_DSP_NAME, ResourceGroupName::new);
        Map<VolumeNumber, VolumeGroup> vlmGrpMap = new TreeMap<>();
        Map<ResourceName, ResourceDefinition> rscDfnMap = new TreeMap<>();

        final Integer replicaCount;
        final List<String> replicasOnSame;
        final List<String> replicasOnDifferent;

        try
        {
            switch (getDbType())
            {
                case ETCD:
                    String replicaCountStr = raw.get(REPLICA_COUNT);
                    replicaCount = replicaCountStr != null ? Integer.parseInt(replicaCountStr) : null;

                    String replicasOnSameStr = raw.get(REPLICAS_ON_SAME);
                    replicasOnSame = replicasOnSameStr != null
                        ? new ArrayList<>(OBJ_MAPPER.readValue(replicasOnSameStr, List.class))
                        : null;

                    String replicasOnDifferentStr = raw.get(REPLICAS_ON_DIFFERENT);
                    replicasOnDifferent = replicasOnDifferentStr != null
                        ? new ArrayList<>(OBJ_MAPPER.readValue(replicasOnDifferentStr, List.class))
                        : null;
                    break;
                case SQL:
                    replicaCount = raw.get(REPLICA_COUNT);

                    replicasOnSame = raw.getAsStringList(REPLICAS_ON_SAME);
                    replicasOnDifferent = raw.getAsStringList(REPLICAS_ON_DIFFERENT);
                    break;
                default:
                    throw new ImplementationError("Unknown database type: " + getDbType());
            }
        }
        catch (IOException exc)
        {
            throw new DatabaseException(exc);
        }
        return new Pair<>(
            new ResourceGroup(
                raw.build(UUID, java.util.UUID::fromString),
                getObjectProtection(ObjectProtection.buildPath(rscGrpName)),
                rscGrpName,
                raw.get(DESCRIPTION),
                DatabaseLoader.asDevLayerKindList(raw.getAsStringList(LAYER_STACK)),
                replicaCount,
                raw.get(POOL_NAME),
                raw.getAsStringList(DO_NOT_PLACE_WITH_RSC_LIST),
                raw.get(DO_NOT_PLACE_WITH_RSC_REGEX),
                replicasOnSame,
                replicasOnDifferent,
                DatabaseLoader.asDevLayerProviderList(raw.getAsStringList(ALLOWED_PROVIDER_LIST)),
                raw.get(DISKLESS_ON_REMAINING),
                vlmGrpMap,
                rscDfnMap,
                this,
                propsContainerFactory,
                transObjFactory,
                transMgrProvider
            ),
            new RscGrpInitMapsImpl(vlmGrpMap, rscDfnMap)
        );
    }

    @Override
    protected String getId(ResourceGroup rscGrp)
    {
        return "(RscGrpName=" + rscGrp.getName().displayValue + ")";
    }

    private class RscGrpInitMapsImpl implements ResourceGroup.InitMaps
    {
        private final Map<VolumeNumber, VolumeGroup> vlmGrpMap;
        private final Map<ResourceName, ResourceDefinition> rscDfnMap;

        RscGrpInitMapsImpl(
            Map<VolumeNumber, VolumeGroup> vlmGrpMapRef,
            Map<ResourceName, ResourceDefinition> rscDfnMapRef
        )
        {
            vlmGrpMap = vlmGrpMapRef;
            rscDfnMap = rscDfnMapRef;
        }

        @Override
        public Map<VolumeNumber, VolumeGroup> getVlmGrpMap()
        {
            return vlmGrpMap;
        }

        @Override
        public Map<ResourceName, ResourceDefinition> getRscDfnMap()
        {
            return rscDfnMap;
        }

    }
}
