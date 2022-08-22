package com.linbit.linstor.core.apicallhandler.controller;

import com.linbit.ImplementationError;
import com.linbit.SizeConv;
import com.linbit.SizeConv.SizeUnit;
import com.linbit.extproc.ExtCmd;
import com.linbit.linstor.LinStorDataAlreadyExistsException;
import com.linbit.linstor.LinstorParsingUtils;
import com.linbit.linstor.PriorityProps;
import com.linbit.linstor.annotation.ApiContext;
import com.linbit.linstor.annotation.PeerContext;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiCallRcWith;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.prop.LinStorObject;
import com.linbit.linstor.compat.CompatibilityUtils;
import com.linbit.linstor.core.BackupInfoManager;
import com.linbit.linstor.core.LinStor;
import com.linbit.linstor.core.SharedResourceManager;
import com.linbit.linstor.core.apicallhandler.controller.helpers.ResourceCreateCheck;
import com.linbit.linstor.core.apicallhandler.controller.internal.CtrlSatelliteUpdateCaller;
import com.linbit.linstor.core.apicallhandler.response.ApiAccessDeniedException;
import com.linbit.linstor.core.apicallhandler.response.ApiDatabaseException;
import com.linbit.linstor.core.apicallhandler.response.ApiRcException;
import com.linbit.linstor.core.apicallhandler.response.CtrlResponseUtils;
import com.linbit.linstor.core.apicallhandler.response.ResponseContext;
import com.linbit.linstor.core.apicallhandler.response.ResponseConverter;
import com.linbit.linstor.core.apicallhandler.response.ResponseUtils;
import com.linbit.linstor.core.apis.VolumeApi;
import com.linbit.linstor.core.identifier.NodeName;
import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.core.identifier.VolumeNumber;
import com.linbit.linstor.core.objects.Node;
import com.linbit.linstor.core.objects.Resource;
import com.linbit.linstor.core.objects.Resource.Flags;
import com.linbit.linstor.core.objects.ResourceControllerFactory;
import com.linbit.linstor.core.objects.ResourceDefinition;
import com.linbit.linstor.core.objects.Snapshot;
import com.linbit.linstor.core.objects.StorPool;
import com.linbit.linstor.core.objects.Volume;
import com.linbit.linstor.core.objects.VolumeDefinition;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.event.EventWaiter;
import com.linbit.linstor.event.ObjectIdentifier;
import com.linbit.linstor.event.common.ResourceState;
import com.linbit.linstor.event.common.ResourceStateEvent;
import com.linbit.linstor.layer.LayerPayload;
import com.linbit.linstor.layer.resource.CtrlRscLayerDataFactory;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.netcom.PeerNotConnectedException;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.propscon.InvalidValueException;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.stateflags.FlagsHelper;
import com.linbit.linstor.stateflags.StateFlags;
import com.linbit.linstor.storage.StorageConstants;
import com.linbit.linstor.storage.data.RscLayerSuffixes;
import com.linbit.linstor.storage.data.adapter.drbd.DrbdRscData;
import com.linbit.linstor.storage.interfaces.categories.resource.AbsRscLayerObject;
import com.linbit.linstor.storage.interfaces.categories.resource.VlmProviderObject;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;
import com.linbit.linstor.storage.kinds.DeviceProviderKind;
import com.linbit.linstor.storage.utils.LayerUtils;
import com.linbit.linstor.tasks.ScheduleBackupService;
import com.linbit.linstor.utils.layer.DrbdLayerUtils;
import com.linbit.utils.AccessUtils;
import com.linbit.utils.Pair;
import com.linbit.utils.StringUtils;

import static com.linbit.linstor.api.ApiConsts.MASK_STOR_POOL;
import static com.linbit.linstor.api.ApiConsts.MASK_WARN;
import static com.linbit.linstor.core.apicallhandler.controller.CtrlRscApiCallHandler.getRscDescriptionInline;
import static com.linbit.linstor.core.apicallhandler.controller.CtrlRscDfnApiCallHandler.getRscDfnDescriptionInline;
import static com.linbit.linstor.core.apicallhandler.controller.CtrlVlmDfnApiCallHandler.getVlmDfnDescriptionInline;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Singleton
public class CtrlRscCrtApiHelper
{
    private final AccessContext apiCtx;
    private final ErrorReporter errorReporter;
    private final CtrlPropsHelper ctrlPropsHelper;
    private final CtrlVlmCrtApiHelper ctrlVlmCrtApiHelper;
    private final EventWaiter eventWaiter;
    private final ResourceStateEvent resourceStateEvent;
    private final CtrlSatelliteUpdateCaller ctrlSatelliteUpdateCaller;
    private final ResponseConverter responseConverter;
    private final CtrlApiDataLoader ctrlApiDataLoader;
    private final ResourceControllerFactory resourceFactory;
    private final Provider<AccessContext> peerAccCtx;
    private final ResourceCreateCheck resourceCreateCheck;
    private final CtrlRscLayerDataFactory layerDataHelper;
    private final Provider<CtrlRscAutoHelper> autoHelper;
    private final Provider<CtrlRscToggleDiskApiCallHandler> toggleDiskHelper;
    private final SharedResourceManager sharedRscMgr;
    private final BackupInfoManager backupInfoMgr;
    private final ScheduleBackupService scheduleBackupService;
    private final CtrlRscLayerDataFactory ctrlRscLayerDataFactory;
    private final CtrlRscActivateApiCallHandler ctrlRscActivateApiCallHandler;

    @Inject
    CtrlRscCrtApiHelper(
        @ApiContext AccessContext apiCtxRef,
        ErrorReporter errorReporterRef,
        CtrlPropsHelper ctrlPropsHelperRef,
        CtrlVlmCrtApiHelper ctrlVlmCrtApiHelperRef,
        EventWaiter eventWaiterRef,
        ResourceStateEvent resourceStateEventRef,
        CtrlSatelliteUpdateCaller ctrlSatelliteUpdateCallerRef,
        ResponseConverter responseConverterRef,
        CtrlApiDataLoader ctrlApiDataLoaderRef,
        ResourceControllerFactory resourceFactoryRef,
        @PeerContext Provider<AccessContext> peerAccCtxRef,
        ResourceCreateCheck resourceCreateCheckRef,
        CtrlRscLayerDataFactory layerDataHelperRef,
        Provider<CtrlRscAutoHelper> autoHelperRef,
        Provider<CtrlRscToggleDiskApiCallHandler> toggleDiskHelperRef,
        SharedResourceManager sharedRscMgrRef,
        BackupInfoManager backupInfoMgrRef,
        ScheduleBackupService scheduleBackupServiceRef,
        CtrlRscLayerDataFactory ctrlRscLayerDataFactoryRef,
        CtrlRscActivateApiCallHandler ctrlRscActivateApiCallHandlerRef
    )
    {
        apiCtx = apiCtxRef;
        errorReporter = errorReporterRef;
        ctrlPropsHelper = ctrlPropsHelperRef;
        ctrlVlmCrtApiHelper = ctrlVlmCrtApiHelperRef;
        eventWaiter = eventWaiterRef;
        resourceStateEvent = resourceStateEventRef;
        ctrlSatelliteUpdateCaller = ctrlSatelliteUpdateCallerRef;
        responseConverter = responseConverterRef;
        ctrlApiDataLoader = ctrlApiDataLoaderRef;
        resourceFactory = resourceFactoryRef;
        peerAccCtx = peerAccCtxRef;
        resourceCreateCheck = resourceCreateCheckRef;
        layerDataHelper = layerDataHelperRef;
        autoHelper = autoHelperRef;
        toggleDiskHelper = toggleDiskHelperRef;
        sharedRscMgr = sharedRscMgrRef;
        backupInfoMgr = backupInfoMgrRef;
        scheduleBackupService = scheduleBackupServiceRef;
        ctrlRscLayerDataFactory = ctrlRscLayerDataFactoryRef;
        ctrlRscActivateApiCallHandler = ctrlRscActivateApiCallHandlerRef;
    }

    /**
     * This method really creates the resource and its volumes.
     *
     * This method does NOT:
     * * commit any transaction
     * * update satellites
     * * create success-apiCallRc entries (only error RC in case of exception)
     *
     * @return the newly created resource
     */
    public Pair<List<Flux<ApiCallRc>>, ApiCallRcWith<Resource>> createResourceDb(
        String nodeNameStr,
        String rscNameStr,
        long flags,
        Map<String, String> rscPropsMap,
        List<? extends VolumeApi> vlmApiList,
        Integer nodeIdInt,
        Map<StorPool.Key, Long> thinFreeCapacities,
        List<String> layerStackStrListRef
    )
    {
        long adjustedFlags = flags;
        List<Flux<ApiCallRc>> autoFlux = new ArrayList<>();
        Resource rsc;
        ApiCallRcImpl responses = new ApiCallRcImpl();

        AccessContext peerCtx = peerAccCtx.get();
        Node node = ctrlApiDataLoader.loadNode(nodeNameStr, true);
        ResourceDefinition rscDfn = ctrlApiDataLoader.loadRscDfn(rscNameStr, true);
        if (backupInfoMgr.restoreContainsRscDfn(rscDfn))
        {
            throw new ApiRcException(
                ApiCallRcImpl.simpleEntry(
                    ApiConsts.FAIL_IN_USE,
                    rscNameStr + " is currently being restored from a backup. " +
                        "Please wait until the restore is finished"
                )
            );
        }
        if (isNodeFlagSet(node, Node.Flags.EVACUATE))
        {
            throw new ApiRcException(
                ApiCallRcImpl.simpleEntry(
                    ApiConsts.FAIL_EVACUATING,
                    "node '" + nodeNameStr + " is being evacuated. No new resources allowed on this node."
                )
            );
        }

        Resource rscForToggleDiskful = ctrlApiDataLoader.loadRsc(node.getName(), rscDfn.getName(), false);
        if (rscForToggleDiskful != null && !isFlagSet(rscForToggleDiskful, Resource.Flags.DRBD_DISKLESS))
        {
            // diskful resource, do not try to toggle this
            rscForToggleDiskful = null;
        }

        String storPoolName = rscPropsMap.get(ApiConsts.KEY_STOR_POOL_NAME);
        if (rscForToggleDiskful != null)
        {
            rsc = rscForToggleDiskful;
            autoHelper.get().removeTiebreakerFlag(rscForToggleDiskful); // just in case this was a tiebreaker
            String storPoolNameStr = storPoolName;
            StorPool storPool = storPoolNameStr == null ?
                null : ctrlApiDataLoader.loadStorPool(storPoolNameStr, nodeNameStr, false);

            boolean isDiskless = FlagsHelper.isFlagEnabled(adjustedFlags, Resource.Flags.DISKLESS) || // needed for
                                                                                              // compatibility
                FlagsHelper.isFlagEnabled(adjustedFlags, Resource.Flags.DRBD_DISKLESS) ||
                FlagsHelper.isFlagEnabled(adjustedFlags, Resource.Flags.NVME_INITIATOR) ||
                FlagsHelper.isFlagEnabled(adjustedFlags, Resource.Flags.EBS_INITIATOR) ||
                (storPool != null && storPool.getDeviceProviderKind().equals(DeviceProviderKind.DISKLESS));

            if (FlagsHelper.isFlagEnabled(adjustedFlags, Resource.Flags.INACTIVE))
            {
                autoFlux.add(ctrlRscActivateApiCallHandler.deactivateRsc(nodeNameStr, rscNameStr));
            }

            if (!isDiskless)
            {
                // target resource is diskful
                autoFlux.add(
                    toggleDiskHelper.get().resourceToggleDisk(
                        nodeNameStr,
                        rscNameStr,
                        storPoolNameStr,
                        null,
                        layerStackStrListRef,
                        false
                    )
                );
            }
            else
            {
                if (isFlagSet(rscForToggleDiskful, Resource.Flags.TIE_BREAKER))
                {
                    // target resource is diskless.
                    NodeName tiebreakerNodeName = rscForToggleDiskful.getNode().getName();
                    autoFlux.add(
                        ctrlSatelliteUpdateCaller.updateSatellites(
                            rscForToggleDiskful.getDefinition(),
                            Flux.empty() // if failed, there is no need for the retry-task to wait for readyState
                            // this is only true as long as there is no other flux concatenated after readyResponses
                        )
                            .transform(
                                updateResponses -> CtrlResponseUtils.combineResponses(
                                    updateResponses,
                                    rscDfn.getName(),
                                    Collections.singleton(tiebreakerNodeName),
                                    "Removed TIE_BREAKER flag from resource {1} on {0}",
                                    "Update of resource {1} on '" + tiebreakerNodeName + "' applied on node {0}"
                                )
                            )
                    );
                }
                else
                {
                    // noop, resource is already diskless as expected
                }
            }
        }
        else
        {
            LayerPayload payload = new LayerPayload();
            payload.getDrbdRsc().nodeId = nodeIdInt;
            if (storPoolName != null)
            {
                // null if resource is created with "-d" (diskless)
                StorPool storPool = ctrlApiDataLoader.loadStorPool(storPoolName, nodeNameStr, true);

                Iterator<VolumeDefinition> vlmDfnIt = getVlmDfnIterator(rscDfn);
                while (vlmDfnIt.hasNext())
                {
                    /*
                     * We need to add this to avoid a chicken-egg problem:
                     *
                     * after creating the resource-instance, the RscFactory also creates all necessary layer-objects
                     * the DRBD-layer-object needs to know whether or not the resource is placed within a shared storage
                     * pool
                     * however, we cannot ask the volumes of the resource, as those do not exist at the given time.
                     * the resource-properties are also only copied into the resource-instance after its creation (duh)
                     */
                    VolumeDefinition vlmDfn = vlmDfnIt.next();
                    payload.putStorageVlmPayload(
                        RscLayerSuffixes.SUFFIX_DATA,
                        vlmDfn.getVolumeNumber().value,
                        storPool
                    );
                }
            }

            List<DeviceLayerKind> layerStack = getLayerstackOrBuildDefault(
                peerCtx,
                layerDataHelper,
                errorReporter,
                layerStackStrListRef,
                responses,
                rscDfn
            );

            // compatibility
            String storPoolNameStr = storPoolName;
            StorPool storPool = storPoolNameStr == null ?
                null :
                ctrlApiDataLoader.loadStorPool(
                    storPoolNameStr,
                    nodeNameStr,
                    false
                );

            boolean isStorPoolDiskless = false;
            boolean isStorPoolOpenflex = false;
            if (storPool != null)
            {
                isStorPoolDiskless = !storPool.getDeviceProviderKind().hasBackingDevice();
                isStorPoolOpenflex = storPool.getDeviceProviderKind().equals(DeviceProviderKind.OPENFLEX_TARGET);
            }

            boolean isDisklessSet = FlagsHelper.isFlagEnabled(adjustedFlags, Resource.Flags.DISKLESS);
            boolean isDrbdDisklessSet = FlagsHelper.isFlagEnabled(adjustedFlags, Resource.Flags.DRBD_DISKLESS);
            boolean isNvmeInitiatorSet = FlagsHelper.isFlagEnabled(adjustedFlags, Resource.Flags.NVME_INITIATOR);
            boolean isEbsInitiatorSet = FlagsHelper.isFlagEnabled(adjustedFlags, Resource.Flags.EBS_INITIATOR);

            if (
                (isDisklessSet && !isDrbdDisklessSet && !isNvmeInitiatorSet && !isEbsInitiatorSet) ||
                (!isDisklessSet && isStorPoolDiskless)
            )
            {
                if (layerStack.isEmpty())
                {
                    adjustedFlags |= Resource.Flags.DRBD_DISKLESS.flagValue;
                    responses.addEntry(makeFlaggedDrbdDisklessWarning(storPool));
                }
                else
                {
                    Flags disklessNvmeOrDrbd = CompatibilityUtils.mapDisklessFlagToNvmeOrDrbd(layerStack);
                    if (storPool != null)
                    {
                        if (disklessNvmeOrDrbd.equals(Resource.Flags.DRBD_DISKLESS))
                        {
                            responses.addEntry(makeFlaggedDrbdDisklessWarning(storPool));
                        }
                        else
                        {
                            responses.addEntry(makeFlaggedNvmeInitiatorWarning(storPool));
                        }
                    }
                    adjustedFlags |= disklessNvmeOrDrbd.flagValue;

                    if (
                        FlagsHelper.isFlagEnabled(
                            adjustedFlags,
                            Resource.Flags.DRBD_DISKLESS,
                            Resource.Flags.NVME_INITIATOR
                        )
                    )
                    {
                        throw new ApiRcException(
                            ApiCallRcImpl.simpleEntry(
                                ApiConsts.FAIL_INVLD_LAYER_STACK,
                                "Could not figure out how to interpret the deprecated --diskless flag."
                            )
                                .setDetails(
                                    "The general DISKLESS flag is deprecated. If both layers, DRBD and NVME, should " +
                                        "be used LINSTOR has to figure out if the resource should be diskful for DRBD " +
                                        "(required NVME_INITIATOR) or diskless for DRBD (requires DRBD_DISKLESS). " +
                                        "Using the deprecated DISKLESS flag is not supported for this case."
                                )
                                .setCorrection("Use either a non-deprecated flag or do not use both layers")
                        );
                    }
                }
            }

            if (isStorPoolOpenflex && !layerStack.contains(DeviceLayerKind.OPENFLEX))
            {
                throw new ApiRcException(
                    ApiCallRcImpl.simpleEntry(
                        ApiConsts.FAIL_INVLD_LAYER_STACK,
                        "Selecting a storage pool with the openflex driver requires OPENFLEX to be included in the layer-list"
                    )
                );
            }

            rsc = createResource(rscDfn, node, payload, adjustedFlags, layerStack);
            Props rscProps = ctrlPropsHelper.getProps(rsc);

            ctrlPropsHelper.fillProperties(
                responses, LinStorObject.RESOURCE, rscPropsMap, rscProps, ApiConsts.FAIL_ACC_DENIED_RSC
            );

            if (ctrlVlmCrtApiHelper.isDiskless(rsc) && storPoolNameStr == null)
            {
                rscProps.map().put(ApiConsts.KEY_STOR_POOL_NAME, LinStor.DISKLESS_STOR_POOL_NAME);
            }

            for (VolumeApi vlmApi : vlmApiList)
            {
                VolumeDefinition vlmDfn = loadVlmDfn(rscDfn, vlmApi.getVlmNr(), true);

                Volume vlmData = ctrlVlmCrtApiHelper.createVolumeResolvingStorPool(
                    rsc,
                    vlmDfn,
                    thinFreeCapacities
                ).extractApiCallRc(responses);

                Props vlmProps = ctrlPropsHelper.getProps(vlmData);

                ctrlPropsHelper.fillProperties(
                    responses, LinStorObject.VOLUME, vlmApi.getVlmProps(), vlmProps, ApiConsts.FAIL_ACC_DENIED_VLM
                );
            }

            Iterator<VolumeDefinition> iterateVolumeDfn = getVlmDfnIterator(rscDfn);
            while (iterateVolumeDfn.hasNext())
            {
                VolumeDefinition vlmDfn = iterateVolumeDfn.next();

                // first check if we probably just deployed a vlm for this vlmDfn
                if (rsc.getVolume(vlmDfn.getVolumeNumber()) == null)
                {
                    // not deployed yet.

                    Volume vlm = ctrlVlmCrtApiHelper.createVolumeResolvingStorPool(
                        rsc,
                        vlmDfn,
                        thinFreeCapacities
                    ).extractApiCallRc(responses);

                    setDrbdPropsForThinVolumesIfNeeded(vlm);
                }
            }

            resourceCreateCheck.checkCreatedResource(rsc);
        }

        if (!isFlagSet(rsc, Resource.Flags.INACTIVE) && !sharedRscMgr.isActivationAllowed(rsc))
        {
            autoFlux.add(ctrlRscActivateApiCallHandler.activateRsc(nodeNameStr, rscNameStr));
        }

        return new Pair<>(autoFlux, new ApiCallRcWith<>(responses, rsc));
    }

    static List<DeviceLayerKind> getLayerstackOrBuildDefault(
        AccessContext accCtx,
        CtrlRscLayerDataFactory layerDataHelper,
        ErrorReporter errorReporter,
        List<String> layerStackStrListRef,
        ApiCallRcImpl responses,
        ResourceDefinition rscDfn
    )
    {
        List<DeviceLayerKind> layerStack = LinstorParsingUtils.asDeviceLayerKind(layerStackStrListRef);

        if (layerStack.isEmpty())
        {
            layerStack = getLayerStack(accCtx, rscDfn);
            if (layerStack.isEmpty())
            {
                Set<List<DeviceLayerKind>> existingLayerStacks = extractExistingLayerStacks(
                    accCtx,
                    layerDataHelper,
                    rscDfn
                );
                switch (existingLayerStacks.size())
                {
                    case 0: // ignore, will be filled later by CtrlLayerDataHelper#createDefaultLayerStack
                        // but that method requires the resource to already exist.
                        break;
                    case 1:
                        layerStack = existingLayerStacks.iterator().next();
                        break;
                    default:
                        throw new ApiRcException(
                            ApiCallRcImpl.simpleEntry(
                                ApiConsts.FAIL_INVLD_LAYER_STACK,
                                "Could not figure out what layer-list to default to."
                            )
                                .setDetails(
                                    "Layer lists of already existing resources: \n   " +
                                        StringUtils.join(existingLayerStacks, "\n   ")
                                )
                                .setCorrection("Please specify a layer-list")
                        );

                }
            }
        }
        else
        {
            if (
                !layerStack.get(layerStack.size() - 1).equals(DeviceLayerKind.STORAGE) &&
                    !layerStack.contains(DeviceLayerKind.OPENFLEX)
            )
            {
                layerStack.add(DeviceLayerKind.STORAGE);
                warnAddedStorageLayer(errorReporter, responses);
            }
        }
        return layerStack;
    }

    private static void warnAddedStorageLayer(ErrorReporter errorReporter, ApiCallRcImpl responsesRef)
    {
        String warnMsg = "The layerstack was extended with STORAGE kind.";
        errorReporter.logWarning(warnMsg);

        responsesRef.addEntry(
            ApiCallRcImpl.entryBuilder(
                ApiConsts.WARN_STORAGE_KIND_ADDED,
                warnMsg
            )
            .setDetails("Layer stacks have to be based on STORAGE kind. Layers configured to be diskless\n" +
                "will not use the additional STORAGE layer.")
            .build()
        );
    }

    private void setDrbdPropsForThinVolumesIfNeeded(Volume vlmRef)
    {
        try
        {
            AccessContext peerCtx = peerAccCtx.get();
            AbsRscLayerObject<Resource> rscLayerObj = vlmRef.getAbsResource().getLayerData(peerCtx);
            if (LayerUtils.hasLayer(rscLayerObj, DeviceLayerKind.DRBD))
            {
                boolean hasThinStorPool = false;
                boolean hasFatStorPool = false;
                TreeSet<Long> supportedGranularities = new TreeSet<>();
                supportedGranularities.add(8L); // take ZFS, unless we have at least one LVM
                // only set true on ZFS, see internal gitlab issue 671 for details
                boolean discardZerosIfAligned = false;

                List<AbsRscLayerObject<Resource>> storageRscLayerObjList = LayerUtils.getChildLayerDataByKind(
                    rscLayerObj,
                    DeviceLayerKind.STORAGE
                );
                for (AbsRscLayerObject<Resource> storageRsc : storageRscLayerObjList)
                {
                    if (RscLayerSuffixes.isNonMetaDataLayerSuffix(storageRsc.getResourceNameSuffix()))
                    {
                        for (VlmProviderObject<Resource> storageVlm : storageRsc.getVlmLayerObjects().values())
                        {
                            StorPool storPool = storageVlm.getStorPool();
                            DeviceProviderKind devProviderKind = storPool.getDeviceProviderKind();
                            switch (devProviderKind)
                            {
                                case OPENFLEX_TARGET: // fall-through
                                case DISKLESS: // ignored
                                    break;
                                case LVM: // fall-through
                                case SPDK: // fall-through
                                case REMOTE_SPDK: // fall-through
                                case EBS_INIT: // fall-through
                                case EBS_TARGET: // fall-through
                                case EXOS:
                                    hasFatStorPool = true;
                                    break;
                                case FILE:
                                    // TODO: introduce storage pool specific distinction about this
                                    hasFatStorPool = true;
                                    break;
                                case LVM_THIN:
                                {
                                    String granularity = storPool.getProps(apiCtx).getProp(
                                        StorageConstants.KEY_INT_THIN_POOL_GRANULARITY,
                                        StorageConstants.NAMESPACE_INTERNAL
                                    );
                                    if (granularity == null)
                                    {
                                        granularity = "64";
                                        errorReporter.logWarning(
                                            "StorPool %s of node %s unexpectedly does not have property %s. Using %sk as fallback.",
                                            storPool.getName().displayValue,
                                            storPool.getNode().getName().displayValue,
                                            StorageConstants.KEY_INT_THIN_POOL_GRANULARITY,
                                            granularity
                                        );
                                    }
                                    supportedGranularities.add(Long.parseLong(granularity));

                                    hasThinStorPool = true;
                                    discardZerosIfAligned = true;
                                }
                                    break;
                                case ZFS:
                                    hasFatStorPool = true;
                                    discardZerosIfAligned = true;
                                    break;
                                case ZFS_THIN:
                                    discardZerosIfAligned = true;
                                    // fall-through
                                case FILE_THIN:
                                    hasThinStorPool = true;
                                    break;
                                case FAIL_BECAUSE_NOT_A_VLM_PROVIDER_BUT_A_VLM_LAYER:
                                    // fall-through
                                default:
                                    throw new ImplementationError("Unknown deviceProviderKind: " + devProviderKind);
                            }
                        }
                    }
                }
                if (hasThinStorPool && hasFatStorPool)
                {
                    throw new ApiRcException(
                        ApiCallRcImpl.simpleEntry(
                            ApiConsts.FAIL_INVLD_STOR_DRIVER,
                            "Mixing thin and thick storage pools are not allowed"
                        )
                    );
                }

                //TODO: make these default drbd-properties configurable (provider-specific?)

                ResourceDefinition rscDfn = vlmRef.getVolumeDefinition().getResourceDefinition();
                Props vlmDfnProps = vlmRef.getVolumeDefinition().getProps(peerCtx);
                PriorityProps prioProps = new PriorityProps(vlmDfnProps,
                    rscDfn.getProps(peerCtx),
                    rscDfn.getResourceGroup().getVolumeGroupProps(peerCtx, vlmRef.getVolumeNumber()),
                    rscDfn.getResourceGroup().getProps(peerCtx)
                );
                if (prioProps.getProp("rs-discard-granularity", ApiConsts.NAMESPC_DRBD_DISK_OPTIONS) == null)
                {
                    String granularity = Long.toString(
                        SizeConv.convert(
                            supportedGranularities.last(), // take the largest granularity from all storage pools
                            SizeUnit.UNIT_KiB,
                            SizeUnit.UNIT_B
                        )
                    );
                    vlmDfnProps.setProp("rs-discard-granularity", granularity,  ApiConsts.NAMESPC_DRBD_DISK_OPTIONS);
                }
                if (prioProps.getProp("discard-zeroes-if-aligned", ApiConsts.NAMESPC_DRBD_DISK_OPTIONS) == null)
                {
                    vlmDfnProps.setProp(
                        "discard-zeroes-if-aligned",
                        discardZerosIfAligned ? "yes" : "no",
                        ApiConsts.NAMESPC_DRBD_DISK_OPTIONS);
                }
            }
        }
        catch (AccessDeniedException exc)
        {
            throw new ApiAccessDeniedException(
                exc,
                "setting properties on volume",
                ApiConsts.FAIL_ACC_DENIED_VLM_DFN
            );
        }
        catch (InvalidKeyException | InvalidValueException exc)
        {
            throw new ImplementationError("Invalid hardcoded thin-volume related properties", exc);
        }
        catch (DatabaseException sqlExc)
        {
            throw new ApiDatabaseException(sqlExc);
        }
    }

    public Flux<ApiCallRc> deployResources(ResponseContext context, Set<Resource> deployedResources)
    {
        return deployResources(context, deployedResources, true);
    }

    public Publisher<ApiCallRc> waitResourcesReady(
        ResponseContext context,
        ResourceDefinition rscDfn,
        Set<Resource> deployedResources)
    {
        final ResourceName rscName = rscDfn.getName();
        Publisher<ApiCallRc> readyResponses;
        if (getVolumeDfnCountPrivileged(rscDfn) == 0)
        {
            // No DRBD resource is created when no volumes are present, so do not wait for it to be ready
            readyResponses = Mono.just(responseConverter.addContextAll(
                makeNoVolumesMessage(rscName), context, false));
        }
        else
        if (allDiskless(rscDfn))
        {
            readyResponses = Mono.just(makeAllDisklessMessage(rscName));
        }
        else
        {
            List<Mono<ApiCallRc>> resourceReadyResponses = new ArrayList<>();
            for (Resource rsc : deployedResources)
            {
                if (
                    AccessUtils.execPrivileged(
                        () -> DrbdLayerUtils.isAnyDrbdResourceExpected(apiCtx, rsc)
                    )
                )
                {
                    NodeName nodeName = rsc.getNode().getName();
                    if (containsDrbdLayerData(rsc))
                    {
                        resourceReadyResponses.add(
                            eventWaiter
                                .waitForStream(
                                    resourceStateEvent.get(),
                                    // TODO if anything is allowed above DRBD, this resource-name must be adjusted
                                    ObjectIdentifier.resource(nodeName, rscName))
                                .skipUntil(ResourceState::getResourceReady)
                                .timeout(Duration.ofMillis(ExtCmd.dfltWaitTimeout))
                                .next()
                                .thenReturn(makeResourceReadyMessage(context, nodeName, rscName))
                                .onErrorResume(
                                    PeerNotConnectedException.class, ignored -> Mono.just(
                                        ApiCallRcImpl.singletonApiCallRc(ResponseUtils.makeNotConnectedWarning(nodeName))
                                    ))
                                .onErrorResume(TimeoutException.class, te -> makeRdyTimeoutApiRc(nodeName))
                        );
                    }
                }
            }
            readyResponses = Flux.merge(resourceReadyResponses);
        }
        return readyResponses;
    }

    /**
     * Deploy at least one resource of a resource definition to the satellites and wait for them to be ready.
     */
    public Flux<ApiCallRc> deployResources(
        ResponseContext context,
        Set<Resource> deployedResources,
        boolean waitForReady)
    {
        long rscDfnCount = deployedResources.stream()
            .map(Resource::getDefinition)
            .map(ResourceDefinition::getName)
            .distinct()
            .count();
        if (rscDfnCount != 1)
        {
            throw new IllegalArgumentException("Resources belonging to precisely one resource definition expected");
        }

        ResourceDefinition rscDfn = deployedResources.iterator().next().getDefinition();
        ResourceName rscName = rscDfn.getName();

        Set<NodeName> nodeNames = deployedResources.stream()
            .map(Resource::getNode)
            .map(Node::getName)
            .collect(Collectors.toSet());

        String nodeNamesStr = nodeNames.stream()
            .map(NodeName::getDisplayName)
            .map(displayName -> "''" + displayName + "''")
            .collect(Collectors.joining(", "));

        Publisher<ApiCallRc> readyResponses = waitForReady ?
            waitResourcesReady(context, rscDfn, deployedResources) : Flux.empty();

        Flux<ApiCallRc> taskFlux = scheduleBackupService.fluxAllNewTasks(rscDfn, peerAccCtx.get());
        return ctrlSatelliteUpdateCaller.updateSatellites(
            rscDfn,
            taskFlux
            // if failed, there is no need for the retry-task to wait for readyState
            // this is only true as long as there is no other flux concatenated after readyResponses
        )
            .transform(updateResponses -> CtrlResponseUtils.combineResponses(
                updateResponses,
                rscName,
                nodeNames,
                "Created resource {1} on {0}",
                "Added peer(s) " + nodeNamesStr + " to resource {1} on {0}"
                )
            )
            .concatWith(readyResponses)
            .concatWith(taskFlux);
    }

    private Mono<ApiCallRc> makeRdyTimeoutApiRc(NodeName nodeName)
    {
        final String msg = String.format(
            "Resource did not become ready on node '%s' within" +
            " reasonable time, check Satellite for errors.", nodeName);
        ApiCallRcImpl.ApiCallRcEntry apiEntry = ApiCallRcImpl.entryBuilder(
                ApiConsts.MASK_ERROR | ApiConsts.MASK_RSC, msg)
            .putObjRef(ApiConsts.KEY_NODE, nodeName.displayValue)
            .build();
        return Mono.just(ApiCallRcImpl.singletonApiCallRc(apiEntry));
    }

    public ApiCallRc makeResourceDidNotAppearMessage(ResponseContext context)
    {
        return ApiCallRcImpl.singletonApiCallRc(responseConverter.addContext(ApiCallRcImpl.simpleEntry(
            ApiConsts.FAIL_UNKNOWN_ERROR,
            "Deployed resource did not appear"
        ), context, true));
    }

    public ApiCallRc makeEventStreamDisappearedUnexpectedlyMessage(ResponseContext context)
    {
        return ApiCallRcImpl.singletonApiCallRc(responseConverter.addContext(ApiCallRcImpl.simpleEntry(
            ApiConsts.FAIL_UNKNOWN_ERROR,
            "Resource disappeared while waiting for it to be ready"
        ), context, true));
    }

    Resource createResource(
        ResourceDefinition rscDfn,
        Node node,
        LayerPayload payload,
        long flags,
        List<DeviceLayerKind> layerStackRef
    )
    {
        if (!layerStackRef.isEmpty())
        {
            ensureLayerStackIsAllowed(layerStackRef);
        }

        Resource rsc;
        try
        {
            checkPeerSlotsForNewPeer(rscDfn);

            rsc = resourceFactory.create(
                peerAccCtx.get(),
                rscDfn,
                node,
                payload,
                Resource.Flags.restoreFlags(flags),
                layerStackRef
            );

            List<DeviceLayerKind> unsupportedLayers = getUnsupportedLayers(peerAccCtx.get(), rsc);
            if (!unsupportedLayers.isEmpty())
            {
                throw new ApiRcException(
                    ApiCallRcImpl.simpleEntry(
                        ApiConsts.FAIL_STLT_DOES_NOT_SUPPORT_LAYER,
                        "Satellite '" + node.getName() + "' does not support the following layers: " + unsupportedLayers
                    )
                );
            }
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ApiAccessDeniedException(
                accDeniedExc,
                "register the " + getRscDescriptionInline(node, rscDfn),
                ApiConsts.FAIL_ACC_DENIED_RSC
            );
        }
        catch (DatabaseException sqlExc)
        {
            throw new ApiDatabaseException(sqlExc);
        }
        catch (LinStorDataAlreadyExistsException dataAlreadyExistsExc)
        {
            throw new ApiRcException(ApiCallRcImpl.simpleEntry(
                ApiConsts.INFO_RSC_ALREADY_EXISTS,
                "A " + getRscDescriptionInline(node, rscDfn) + " already exists.",
                true
            ), dataAlreadyExistsExc);
        }
        return rsc;
    }

    Resource createResourceFromSnapshot(
        ResourceDefinition toRscDfn,
        Node toNode,
        Snapshot fromSnapshotRef
    )
    {
        Resource rsc;
        try
        {
            checkPeerSlotsForNewPeer(toRscDfn);

            rsc = resourceFactory.create(
                peerAccCtx.get(),
                toRscDfn,
                toNode,
                fromSnapshotRef.getLayerData(peerAccCtx.get()),
                new Resource.Flags[0]
            );
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ApiAccessDeniedException(
                accDeniedExc,
                "register the " + getRscDescriptionInline(toNode, toRscDfn),
                ApiConsts.FAIL_ACC_DENIED_RSC
            );
        }
        catch (DatabaseException sqlExc)
        {
            throw new ApiDatabaseException(sqlExc);
        }
        catch (LinStorDataAlreadyExistsException dataAlreadyExistsExc)
        {
            throw new ApiRcException(
                ApiCallRcImpl.simpleEntry(
                    ApiConsts.FAIL_EXISTS_RSC,
                    "A " + getRscDescriptionInline(toNode, toRscDfn) + " already exists.",
                    true
                ),
                dataAlreadyExistsExc
            );
        }
        return rsc;
    }

    static List<DeviceLayerKind> getUnsupportedLayers(AccessContext accCtx, Resource rsc) throws AccessDeniedException
    {
        List<DeviceLayerKind> usedDeviceLayerKinds = LayerUtils.getUsedDeviceLayerKinds(
            rsc.getLayerData(accCtx),
            accCtx
        );
        usedDeviceLayerKinds.removeAll(
            rsc.getNode()
                .getPeer(accCtx)
                .getExtToolsManager().getSupportedLayers()
        );

        return usedDeviceLayerKinds;
    }

    static void ensureLayerStackIsAllowed(List<DeviceLayerKind> layerStackRef)
    {
        if (!LayerUtils.isLayerKindStackAllowed(layerStackRef))
        {
            throw new ApiRcException(
                ApiCallRcImpl.simpleEntry(
                    ApiConsts.FAIL_INVLD_LAYER_STACK,
                    "The layer stack " + layerStackRef + " is invalid"
                )
            );
        }
    }

    private VolumeDefinition loadVlmDfn(
        ResourceDefinition rscDfn,
        int vlmNr,
        boolean failIfNull
    )
    {
        return loadVlmDfn(rscDfn, LinstorParsingUtils.asVlmNr(vlmNr), failIfNull);
    }

    private VolumeDefinition loadVlmDfn(
        ResourceDefinition rscDfn,
        VolumeNumber vlmNr,
        boolean failIfNull
    )
    {
        VolumeDefinition vlmDfn;
        try
        {
            vlmDfn = rscDfn.getVolumeDfn(peerAccCtx.get(), vlmNr);

            if (failIfNull && vlmDfn == null)
            {
                String rscName = rscDfn.getName().displayValue;
                throw new ApiRcException(ApiCallRcImpl
                    .entryBuilder(
                        ApiConsts.FAIL_NOT_FOUND_VLM_DFN,
                        "Volume definition with number '" + vlmNr.value + "' on resource definition '" +
                            rscName + "' not found."
                    )
                    .setCause("The specified volume definition with number '" + vlmNr.value +
                        "' on resource definition '" + rscName + "' could not be found in the database")
                    .setCorrection("Create a volume definition with number '" + vlmNr.value +
                        "' on resource definition '" + rscName + "' first.")
                    .build()
                );
            }

        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ApiAccessDeniedException(
                accDeniedExc,
                "load " + getVlmDfnDescriptionInline(rscDfn.getName().displayValue, vlmNr.value),
                ApiConsts.FAIL_ACC_DENIED_VLM_DFN
            );
        }
        return vlmDfn;
    }

    private void checkPeerSlotsForNewPeer(ResourceDefinition rscDfn)
        throws AccessDeniedException
    {
        int resourceCount = 0;
        Iterator<Resource> rscIter = rscDfn.iterateResource(peerAccCtx.get());
        while (rscIter.hasNext())
        {
            Resource rsc = rscIter.next();
            if (LayerUtils.hasLayer(rsc.getLayerData(peerAccCtx.get()), DeviceLayerKind.DRBD))
            {
                resourceCount++;
            }
        }

        rscIter = rscDfn.iterateResource(peerAccCtx.get());
        while (rscIter.hasNext())
        {
            Resource otherRsc = rscIter.next();

            List<AbsRscLayerObject<Resource>> drbdRscDataList = LayerUtils.getChildLayerDataByKind(
                otherRsc.getLayerData(peerAccCtx.get()),
                DeviceLayerKind.DRBD
            );

            for (AbsRscLayerObject<Resource> rscLayerObj : drbdRscDataList)
            {
                if (((DrbdRscData<Resource>) rscLayerObj).getPeerSlots() < resourceCount)
                {
                    throw new ApiRcException(
                        ApiCallRcImpl.simpleEntry(
                            ApiConsts.FAIL_INSUFFICIENT_PEER_SLOTS,
                            "Resource on node " + otherRsc.getNode().getName().displayValue +
                            " has insufficient peer slots to add another peer"
                        )
                    );
                }
            }
        }
    }

    Iterator<VolumeDefinition> getVlmDfnIterator(ResourceDefinition rscDfn)
    {
        Iterator<VolumeDefinition> iterator;
        try
        {
            iterator = rscDfn.iterateVolumeDfn(apiCtx);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ImplementationError(accDeniedExc);
        }
        return iterator;
    }

    private int getVolumeDfnCountPrivileged(ResourceDefinition rscDfn)
    {
        int volumeDfnCount;
        try
        {
            volumeDfnCount = rscDfn.getVolumeDfnCount(apiCtx);
        }
        catch (AccessDeniedException exc)
        {
            throw new ImplementationError(exc);
        }
        return volumeDfnCount;
    }

    private boolean allDiskless(ResourceDefinition rscDfn)
    {
        boolean allDiskless = true;
        try
        {
            AccessContext accCtx = peerAccCtx.get();
            Iterator<Resource> rscIter = rscDfn.iterateResource(accCtx);
            while (rscIter.hasNext())
            {
                StateFlags<Flags> stateFlags = rscIter.next().getStateFlags();
                final boolean hasAnyDisklessFlagSet = stateFlags.isSomeSet(
                    accCtx,
                    Resource.Flags.DRBD_DISKLESS,
                    Resource.Flags.NVME_INITIATOR,
                    Resource.Flags.EBS_INITIATOR
                );
                if (!hasAnyDisklessFlagSet)
                {
                    allDiskless = false;
                }
            }
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ApiAccessDeniedException(
                accDeniedExc,
                "check diskless state of " + getRscDfnDescriptionInline(rscDfn),
                ApiConsts.FAIL_ACC_DENIED_RSC_DFN
            );
        }
        return allDiskless;
    }

    private boolean containsDrbdLayerData(Resource rsc)
    {
        List<AbsRscLayerObject<Resource>> drbdLayerData;
        try
        {
            drbdLayerData = LayerUtils.getChildLayerDataByKind(
                rsc.getLayerData(peerAccCtx.get()),
                DeviceLayerKind.DRBD
            );
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ApiAccessDeniedException(
                accDeniedExc,
                "scan layer data for DRBD layer " + getRscDescriptionInline(rsc),
                ApiConsts.FAIL_ACC_DENIED_RSC_DFN
            );
        }
        return !drbdLayerData.isEmpty();
    }

    private ApiCallRc makeResourceReadyMessage(
        ResponseContext context,
        NodeName nodeName,
        ResourceName rscName
    )
    {
        return ApiCallRcImpl.singletonApiCallRc(responseConverter.addContext(ApiCallRcImpl.simpleEntry(
            ApiConsts.CREATED,
            "Resource '" + rscName + "' on '" + nodeName + "' ready"
        ), context, true));
    }

    private ApiCallRcImpl makeNoVolumesMessage(ResourceName rscName)
    {
        return ApiCallRcImpl.singletonApiCallRc(ApiCallRcImpl.simpleEntry(
            ApiConsts.WARN_NOT_FOUND,
            "No volumes have been defined for resource '" + rscName + "'"
        ));
    }

    private ApiCallRcImpl makeAllDisklessMessage(ResourceName rscName)
    {
        return ApiCallRcImpl.singletonApiCallRc(ApiCallRcImpl.simpleEntry(
            ApiConsts.WARN_ALL_DISKLESS,
            "Resource '" + rscName + "' is unusable because it is diskless on all its nodes"
        ));
    }

    static List<DeviceLayerKind> getLayerStack(AccessContext accCtx, ResourceDefinition rscDfnRef)
    {
        List<DeviceLayerKind> layerStack;
        try
        {
            layerStack = rscDfnRef.getLayerStack(accCtx);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ApiAccessDeniedException(
                accDeniedExc,
                "accessing layerstack of " + getRscDfnDescriptionInline(rscDfnRef),
                ApiConsts.FAIL_ACC_DENIED_RSC_DFN
            );
        }
        return layerStack;
    }

    static Set<List<DeviceLayerKind>> extractExistingLayerStacks(
        AccessContext accCtx,
        CtrlRscLayerDataFactory layerDataHelperRef,
        ResourceDefinition rscDfn
    )
    {
        Set<List<DeviceLayerKind>> ret;
        try
        {
            ret = rscDfn.streamResource(accCtx).map(
                layerDataHelperRef::getLayerStack
            ).collect(Collectors.toSet());

            /*
             * We might have a toggle-disk here were we just removed the layer-data.
             * Otherwise an empty layer list should not be possible anyways
             */
            ret.remove(Collections.emptyList());
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ApiAccessDeniedException(
                accDeniedExc,
                "accessing resources of " + getRscDfnDescriptionInline(rscDfn),
                ApiConsts.FAIL_ACC_DENIED_RSC_DFN
            );
        }
        return ret;
    }

    private boolean isNodeFlagSet(Node node, Node.Flags... flags)
    {
        boolean ret;
        try
        {
            ret = node.getFlags().isSet(peerAccCtx.get(), flags);
        }
        catch (AccessDeniedException exc)
        {
            throw new ApiAccessDeniedException(
                exc,
                "getting flag for node",
                ApiConsts.FAIL_ACC_DENIED_NODE
            );
        }
        return ret;
    }

    private boolean isFlagSet(Resource rsc, Resource.Flags... flags)
    {
        boolean ret;
        try
        {
            ret = rsc.getStateFlags().isSet(peerAccCtx.get(), flags);
        }
        catch (AccessDeniedException exc)
        {
            throw new ApiAccessDeniedException(
                exc,
                "getting flag for resource",
                ApiConsts.FAIL_ACC_DENIED_RSC
            );
        }
        return ret;
    }

    private void setResourceFlags(Resource rsc, Resource.Flags... flags)
    {
        try
        {
            rsc.getStateFlags().enableFlags(peerAccCtx.get(), flags);
        }
        catch (AccessDeniedException exc)
        {
            throw new ApiAccessDeniedException(
                exc,
                "Setting flag for resource",
                ApiConsts.FAIL_ACC_DENIED_RSC
            );
        }
        catch (DatabaseException exc)
        {
            throw new ApiDatabaseException(exc);
        }
    }

    private ApiCallRcImpl.ApiCallRcEntry makeFlaggedNvmeInitiatorWarning(StorPool storPool)
    {
        return makeFlaggedDiskless(storPool, "nvme initiator");
    }

    private ApiCallRcImpl.ApiCallRcEntry makeFlaggedDrbdDisklessWarning(StorPool storPool)
    {
        return makeFlaggedDiskless(storPool, "drbd diskless");
    }

    private ApiCallRcImpl.ApiCallRcEntry makeFlaggedDiskless(StorPool storPool, String type)
    {
        return ApiCallRcImpl
            .entryBuilder(
                MASK_WARN | MASK_STOR_POOL,
                "Resource will be automatically flagged as " + type
            )
            .setCause(
                String.format(
                    "Used storage pool '%s' is diskless, but resource was not flagged %s",
                    storPool.getName(),
                    type
                )
            )
            .build();
    }

}
