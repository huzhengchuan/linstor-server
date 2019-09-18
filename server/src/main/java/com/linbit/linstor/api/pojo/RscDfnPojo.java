package com.linbit.linstor.api.pojo;

import com.linbit.linstor.api.interfaces.RscDfnLayerDataApi;
import com.linbit.linstor.core.apis.ResourceDefinitionApi;
import com.linbit.linstor.core.apis.ResourceGroupApi;
import com.linbit.linstor.core.objects.VolumeDefinition;
import com.linbit.utils.Pair;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 *
 * @author rpeinthor
 */
public class RscDfnPojo implements ResourceDefinitionApi
{
    private final UUID uuid;
    private final ResourceGroupApi rscGrpApi;
    private final String name;
    private final byte[] externalName;
    private final long flags;
    private final Map<String, String> props;
    private final List<VolumeDefinition.VlmDfnApi> vlmdfns;
    private final List<Pair<String, RscDfnLayerDataApi>> layerData;

    public RscDfnPojo(
        final UUID uuidRef,
        final ResourceGroupApi rscGrpPojoRef,
        final String nameRef,
        final byte[] externalNameRef,
        final long flagsRef,
        final Map<String, String> propsRef,
        final List<VolumeDefinition.VlmDfnApi> vlmdfnsRef,
        final List<Pair<String, RscDfnLayerDataApi>> layerDataRef
    )
    {
        uuid = uuidRef;
        rscGrpApi = rscGrpPojoRef;
        name = nameRef;
        externalName = externalNameRef;
        flags = flagsRef;
        props = propsRef;
        vlmdfns = vlmdfnsRef;
        layerData = layerDataRef;
    }

    @Override
    public UUID getUuid()
    {
        return uuid;
    }

    @Override
    public ResourceGroupApi getResourceGroup()
    {
        return rscGrpApi;
    }

    @Override
    public String getResourceName()
    {
        return name;
    }

    @Override
    public byte[] getExternalName()
    {
        return externalName;
    }

    @Override
    public long getFlags()
    {
        return flags;
    }

    @Override
    public Map<String, String> getProps()
    {
        return props;
    }

    @Override
    public List<VolumeDefinition.VlmDfnApi> getVlmDfnList()
    {
        return vlmdfns;
    }

    @Override
    public List<Pair<String, RscDfnLayerDataApi>> getLayerData()
    {
        return layerData;
    }
}
