package com.linbit.drbdmanage;

import java.util.ArrayList;
import java.util.List;

import com.linbit.drbdmanage.propscon.InvalidKeyException;
import com.linbit.drbdmanage.propscon.Props;
import com.linbit.drbdmanage.security.AccessContext;
import com.linbit.drbdmanage.security.AccessDeniedException;

public class PriorityProps
{
    private final List<Props> propList = new ArrayList<>();

    public PriorityProps(
        AccessContext accCtx,
        NodeConnection nodeConnection,
        ResourceConnection resourceConnection,
        VolumeConnection volumeConnection
    )
        throws AccessDeniedException
    {
        if (volumeConnection != null)
        {
            propList.add(volumeConnection.getProps(accCtx));
        }
        if (resourceConnection != null)
        {
            propList.add(resourceConnection.getProps(accCtx));
        }
        if (nodeConnection != null)
        {
            propList.add(nodeConnection.getProps(accCtx));
        }
    }

    public PriorityProps(Props... props)
    {
        for (Props prop : props)
        {
            addProps(prop);
        }
    }

    public void addProps(Props prop)
    {
        if (prop != null)
        {
            propList.add(prop);
        }
    }

    public String getProp(String key, String namespace) throws InvalidKeyException
    {
        String value = null;
        for (Props prop : propList)
        {
            value = prop.getProp(key, namespace);
            if (value != null)
            {
                break;
            }
        }
        return value;
    }

    public String getProp(String key) throws InvalidKeyException
    {
        return getProp(key, null);
    }
}
