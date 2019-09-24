package com.linbit.linstor.dbdrivers.interfaces;

import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.core.identifier.SnapshotName;
import com.linbit.linstor.core.objects.SnapshotDefinition;
import com.linbit.linstor.core.objects.SnapshotVolumeDefinition;
import com.linbit.linstor.dbdrivers.ControllerDatabaseDriver;
import com.linbit.utils.Pair;

import java.util.Map;

public interface SnapshotVolumeDefinitionCtrlDatabaseDriver extends SnapshotVolumeDefinitionDatabaseDriver,
    ControllerDatabaseDriver<SnapshotVolumeDefinition,
        SnapshotVolumeDefinition.InitMaps,
        Map<Pair<ResourceName, SnapshotName>, ? extends SnapshotDefinition>>
{

}
