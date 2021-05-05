package com.linbit.linstor.core.objects;

import com.linbit.ImplementationError;
import com.linbit.InvalidIpAddressException;
import com.linbit.InvalidNameException;
import com.linbit.ValueOutOfRangeException;
import com.linbit.drbd.md.MdException;
import com.linbit.linstor.annotation.SystemContext;
import com.linbit.linstor.core.identifier.RemoteName;
import com.linbit.linstor.core.objects.S3Remote.InitMaps;
import com.linbit.linstor.dbdrivers.AbsDatabaseDriver;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.dbdrivers.DbEngine;
import com.linbit.linstor.dbdrivers.GeneratedDatabaseTables;
import com.linbit.linstor.dbdrivers.interfaces.S3RemoteCtrlDatabaseDriver;
import com.linbit.linstor.dbdrivers.interfaces.updater.SingleColumnDatabaseDriver;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.propscon.PropsContainerFactory;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.security.ObjectProtection;
import com.linbit.linstor.security.ObjectProtectionDatabaseDriver;
import com.linbit.linstor.stateflags.StateFlagsPersistence;
import com.linbit.linstor.transaction.TransactionObjectFactory;
import com.linbit.linstor.transaction.manager.TransactionMgr;
import com.linbit.utils.Pair;

import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.S3Remotes.ACCESS_KEY;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.S3Remotes.BUCKET;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.S3Remotes.DSP_NAME;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.S3Remotes.ENDPOINT;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.S3Remotes.FLAGS;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.S3Remotes.NAME;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.S3Remotes.REGION;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.S3Remotes.SECRET_KEY;
import static com.linbit.linstor.dbdrivers.GeneratedDatabaseTables.S3Remotes.UUID;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.util.function.Function;

@Singleton
public class S3RemoteDbDriver extends AbsDatabaseDriver<S3Remote, S3Remote.InitMaps, Void>
    implements S3RemoteCtrlDatabaseDriver
{
    protected final PropsContainerFactory propsContainerFactory;
    protected final TransactionObjectFactory transObjFactory;
    protected final Provider<? extends TransactionMgr> transMgrProvider;

    protected final SingleColumnDatabaseDriver<S3Remote, String> endpointDriver;
    protected final SingleColumnDatabaseDriver<S3Remote, String> bucketDriver;
    protected final SingleColumnDatabaseDriver<S3Remote, String> regionDriver;
    protected final SingleColumnDatabaseDriver<S3Remote, String> accessKeyDriver;
    protected final SingleColumnDatabaseDriver<S3Remote, String> secretKeyDriver;
    protected final StateFlagsPersistence<S3Remote> flagsDriver;
    protected final AccessContext dbCtx;

    @Inject
    public S3RemoteDbDriver(
        ErrorReporter errorReporterRef,
        @SystemContext AccessContext dbCtxRef,
        DbEngine dbEngine,
        Provider<TransactionMgr> transMgrProviderRef,
        ObjectProtectionDatabaseDriver objProtDriverRef,
        PropsContainerFactory propsContainerFactoryRef,
        TransactionObjectFactory transObjFactoryRef
    )
    {
        super(errorReporterRef, GeneratedDatabaseTables.S3_REMOTES, dbEngine, objProtDriverRef);
        dbCtx = dbCtxRef;
        transMgrProvider = transMgrProviderRef;
        propsContainerFactory = propsContainerFactoryRef;
        transObjFactory = transObjFactoryRef;

        setColumnSetter(UUID, remote -> remote.getUuid().toString());
        setColumnSetter(NAME, remote -> remote.getName().value);
        setColumnSetter(DSP_NAME, remote -> remote.getName().displayValue);
        setColumnSetter(FLAGS, remote -> remote.getFlags().getFlagsBits(dbCtx));
        setColumnSetter(ENDPOINT, remote -> remote.getUrl(dbCtx));
        setColumnSetter(BUCKET, remote -> remote.getBucket(dbCtx));
        setColumnSetter(REGION, remote -> remote.getRegion(dbCtx));
        setColumnSetter(ACCESS_KEY, remote -> remote.getAccessKey(dbCtx));
        setColumnSetter(SECRET_KEY, remote -> remote.getSecretKey(dbCtx));

        endpointDriver = generateSingleColumnDriver(ENDPOINT, remote -> remote.getUrl(dbCtx), Function.identity());
        bucketDriver = generateSingleColumnDriver(BUCKET, remote -> remote.getBucket(dbCtx), Function.identity());
        regionDriver = generateSingleColumnDriver(REGION, remote -> remote.getRegion(dbCtx), Function.identity());
        accessKeyDriver = generateSingleColumnDriver(
            ACCESS_KEY, remote -> remote.getAccessKey(dbCtx), Function.identity()
        );
        secretKeyDriver = generateSingleColumnDriver(
            SECRET_KEY, remote -> remote.getSecretKey(dbCtx), Function.identity()
        );

        flagsDriver = generateFlagDriver(FLAGS, S3Remote.Flags.class);

    }

    @Override
    public SingleColumnDatabaseDriver<S3Remote, String> getEndpointDriver()
    {
        return endpointDriver;
    }

    @Override
    public SingleColumnDatabaseDriver<S3Remote, String> getBucketDriver()
    {
        return bucketDriver;
    }

    @Override
    public SingleColumnDatabaseDriver<S3Remote, String> getRegionDriver()
    {
        return regionDriver;
    }

    @Override
    public SingleColumnDatabaseDriver<S3Remote, String> getAccessKeyDriver()
    {
        return accessKeyDriver;
    }

    @Override
    public SingleColumnDatabaseDriver<S3Remote, String> getSecretKeyDriver()
    {
        return secretKeyDriver;
    }

    @Override
    public StateFlagsPersistence<S3Remote> getStateFlagsPersistence()
    {
        return flagsDriver;
    }

    @Override
    protected Pair<S3Remote, InitMaps> load(RawParameters raw, Void ignored)
        throws DatabaseException, InvalidNameException, ValueOutOfRangeException, InvalidIpAddressException, MdException
    {
        final RemoteName remoteName = raw.build(DSP_NAME, RemoteName::new);
        final long initFlags;
        switch (getDbType())
        {
            case ETCD:
                initFlags = Long.parseLong(raw.get(FLAGS));
                break;
            case SQL:
                initFlags = raw.get(FLAGS);
                break;
            default:
                throw new ImplementationError("Unknown database type: " + getDbType());
        }
        return new Pair<>(
            new S3Remote(
                getObjectProtection(ObjectProtection.buildPath(remoteName)),
                raw.build(UUID, java.util.UUID::fromString),
                this,
                remoteName,
                initFlags,
                raw.get(ENDPOINT),
                raw.get(BUCKET),
                raw.get(REGION),
                raw.get(ACCESS_KEY),
                raw.get(SECRET_KEY),
                transObjFactory,
                transMgrProvider
            ),
            new InitMapsImpl()
        );
    }

    @Override
    protected String getId(S3Remote dataRef) throws AccessDeniedException
    {
        return "S3Remote(" + dataRef.getName().displayValue + ")";
    }

    private class InitMapsImpl implements S3Remote.InitMaps
    {
        private InitMapsImpl()
        {
        }
    }
}
