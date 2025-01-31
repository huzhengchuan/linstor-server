package com.linbit.linstor.dbcp.migration.k8s.crd;

import com.linbit.linstor.ControllerK8sCrdDatabase;
import com.linbit.linstor.dbdrivers.k8s.crd.GenCrdV1_18_2;
import com.linbit.linstor.dbdrivers.k8s.crd.GenCrdV1_19_1;

@K8sCrdMigration(
    description = "Add ebs remotes table",
    version = 7
)
public class Migration_7_v1_19_1_AddEbsRemoteTable extends BaseK8sCrdMigration
{
    public Migration_7_v1_19_1_AddEbsRemoteTable()
    {
        super(
            GenCrdV1_18_2.createTxMgrContext(),
            GenCrdV1_19_1.createTxMgrContext(),
            GenCrdV1_19_1.createSchemaUpdateContext()
        );
    }

    @Override
    public MigrationResult migrateImpl(ControllerK8sCrdDatabase k8sDbRef) throws Exception
    {
        // update CRD entries for all DatabaseTables
        updateCrdSchemaForAllTables();

        return null;
    }
}
