package com.linbit.linstor.core.apicallhandler.controller.helpers;

import com.linbit.ImplementationError;
import com.linbit.linstor.annotation.ApiContext;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.core.apicallhandler.response.ApiRcException;
import com.linbit.linstor.core.objects.Node;
import com.linbit.linstor.core.objects.ResourceDefinition;
import com.linbit.linstor.core.objects.Volume;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;
import com.linbit.linstor.storage.utils.LayerUtils;
import com.linbit.linstor.utils.layer.LayerRscUtils;

import static com.linbit.linstor.core.apicallhandler.controller.helpers.ApiUtils.execPrivileged;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.List;

/**
 * Class for checking if a resource creation is valid according to type/role-specific constraints
 *
 * @author Rainer Laschober
 * @since v0.9.6
 */
@Singleton
public class ResourceCreateCheck
{
    private AccessContext accessContext;
    private boolean hasDrbd;
    private boolean hasNvmeTarget;
    private boolean hasNvmeInitiator;
    private boolean hasOpenflexTarget;
    private boolean hasOpenflexInitiator;

    @Inject
    public ResourceCreateCheck(@ApiContext AccessContext accessContextRef)
    {
        accessContext = accessContextRef;
    }

    private enum ResourceRole
    {
        NVME_TARGET,
        NVME_INITIATOR,
        OPENFLEX_TARGET
    }

    /**
     * Checks if the resource creation is valid according to its type and role.
     * For example, an NVMe Target has different constraints than a NVMe Initiator
     *
     * @throws ApiRcException if any constraint is violated
     */
    public void checkCreatedResource(List<Volume> volumes)
    {
        ResourceRole resourceRole = getCreatedResourceRole(volumes);
        if (resourceRole != null)
        {
            switch (resourceRole)
            {
                case NVME_TARGET:
                    if (hasNvmeTarget && !hasDrbd)
                    {
                        throw new ApiRcException(ApiCallRcImpl.simpleEntry(
                            ApiConsts.FAIL_EXISTS_NVME_TARGET_PER_RSC_DFN,
                            "Only one NVMe Target per resource definition allowed!")
                        );
                    }
                    break;
                case NVME_INITIATOR:
                    if (!hasNvmeTarget)
                    {
                        throw new ApiRcException(ApiCallRcImpl.simpleEntry(
                            ApiConsts.FAIL_MISSING_NVME_TARGET,
                            "An NVMe Target needs to be created before the Initiator!")
                        );
                    }
                    break;
                case OPENFLEX_TARGET:
                    if (hasOpenflexTarget)
                    {
                        throw new ApiRcException(
                            ApiCallRcImpl.simpleEntry(
                                ApiConsts.FAIL_EXISTS_OPENFLEX_TARGET_PER_RSC_DFN,
                                "Only one openflex target per resource definition allowed!"
                            )
                        );
                    }
                    break;
                default:
                    // no further checks needed in this case
            }
        }
    }

    /**
     * @param volumes
     *     List<Volume> of the resource being created
     */
    private ResourceRole getCreatedResourceRole(List<Volume> volumes)
    {
        ResourceRole ret = null;

        try
        {
            for (Volume vlm : volumes)
            {
                List<DeviceLayerKind> layerStack = LayerUtils.getLayerStack(vlm.getAbsResource(), accessContext);
                if (layerStack.contains(DeviceLayerKind.NVME))
                {
                    if (vlm.getAbsResource().getNode().getNodeType(accessContext).equals(Node.Type.OPENFLEX_TARGET))
                    {
                        ret = ResourceRole.OPENFLEX_TARGET;
                    }
                    else
                    {
                        ret = vlm.getAbsResource().isNvmeInitiator(accessContext) ?
                            ResourceRole.NVME_INITIATOR : ResourceRole.NVME_TARGET;
                    }
                }
            }
        }
        catch (AccessDeniedException exc)
        {
            throw new ImplementationError(exc);
        }

        if (volumes.stream().anyMatch(
            vlm -> execPrivileged(
                    () -> LayerRscUtils.getLayerStack(vlm.getAbsResource(), accessContext).contains(DeviceLayerKind.NVME) &&
                    !vlm.getAbsResource().isNvmeInitiator(accessContext)
            )
        ))
        {
            ret = ResourceRole.NVME_TARGET;
        }
        else
        if (
            volumes.stream().anyMatch(
                vlm -> execPrivileged(
                    () -> LayerRscUtils.getLayerStack(vlm.getAbsResource(), accessContext).contains(DeviceLayerKind.NVME) &&
                        vlm.getAbsResource().isNvmeInitiator(accessContext)
                )
            )
        )
        {
            ret = ResourceRole.NVME_INITIATOR;
        }

        return ret;
    }

    /**
     * Queries a resource definition for existing resources of specific roles (currently NVMe
     * Target/Initiator)
     *
     * @param rscDfn
     *     ResourceDefinition potentially containing a resource of certain roles
     */
    public void getAndSetDeployedResourceRoles(ResourceDefinition rscDfn)
    {
        hasNvmeTarget = execPrivileged(
            () -> rscDfn.streamResource(accessContext)).anyMatch(
                rsc -> execPrivileged(
                    () -> LayerRscUtils.getLayerStack(rsc, accessContext).contains(DeviceLayerKind.NVME) &&
                    !rsc.isNvmeInitiator(accessContext)
                )
        );
        hasNvmeInitiator = execPrivileged(
            () -> rscDfn.streamResource(accessContext)).anyMatch(
                rsc -> execPrivileged(
                    () -> LayerRscUtils.getLayerStack(rsc, accessContext).contains(DeviceLayerKind.NVME) &&
                    rsc.isNvmeInitiator(accessContext)
                )
        );
        hasDrbd = execPrivileged(() -> !rscDfn.getLayerData(accessContext, DeviceLayerKind.DRBD).isEmpty());
    }
}
