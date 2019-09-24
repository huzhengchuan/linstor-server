package com.linbit.linstor.dbdrivers.interfaces;

import com.linbit.linstor.core.identifier.NodeName;
import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.core.identifier.SnapshotName;
import com.linbit.linstor.core.identifier.StorPoolName;
import com.linbit.linstor.core.identifier.VolumeNumber;
import com.linbit.linstor.core.objects.Snapshot;
import com.linbit.linstor.core.objects.SnapshotVolume;
import com.linbit.linstor.core.objects.SnapshotVolumeDefinition;
import com.linbit.linstor.core.objects.StorPool;
import com.linbit.linstor.dbdrivers.ControllerDatabaseDriver;
import com.linbit.utils.Pair;
import com.linbit.utils.Triple;

import java.util.Map;

public interface SnapshotVolumeCtrlDatabaseDriver extends SnapshotVolumeDatabaseDriver,
    ControllerDatabaseDriver<SnapshotVolume,
        Void,
        Triple<Map<Triple<NodeName, ResourceName, SnapshotName>, ? extends Snapshot>,
            Map<Triple<ResourceName, SnapshotName, VolumeNumber>, ? extends SnapshotVolumeDefinition>,
            Map<Pair<NodeName, StorPoolName>, ? extends StorPool>>>
{

}
