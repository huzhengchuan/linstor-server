package com.linbit.linstor.storage.layer.provider.file;

import com.linbit.ImplementationError;
import com.linbit.extproc.ExtCmdFactory;
import com.linbit.linstor.annotation.DeviceManagerContext;
import com.linbit.linstor.core.StltConfigAccessor;
import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.core.identifier.VolumeNumber;
import com.linbit.linstor.core.objects.SnapshotVolume;
import com.linbit.linstor.core.objects.StorPool;
import com.linbit.linstor.core.objects.StorPoolData;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.storage.StorageConstants;
import com.linbit.linstor.storage.StorageException;
import com.linbit.linstor.storage.data.provider.file.FileData;
import com.linbit.linstor.storage.interfaces.categories.resource.VlmProviderObject.Size;
import com.linbit.linstor.storage.kinds.DeviceProviderKind;
import com.linbit.linstor.storage.layer.DeviceLayer.NotificationListener;
import com.linbit.linstor.storage.layer.provider.AbsStorageProvider;
import com.linbit.linstor.storage.layer.provider.WipeHandler;
import com.linbit.linstor.storage.layer.provider.utils.StorageConfigReader;
import com.linbit.linstor.storage.utils.DeviceLayerUtils;
import com.linbit.linstor.storage.utils.FileCommands;
import com.linbit.linstor.storage.utils.FileUtils;
import com.linbit.linstor.storage.utils.FileUtils.FileInfo;
import com.linbit.linstor.storage.utils.LosetupCommands;
import com.linbit.linstor.transaction.TransactionMgr;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.UUID;

@Singleton
public class FileProvider extends AbsStorageProvider<FileInfo, FileData>
{
    private static final String DUMMY_LINSTOR_TEST_SOURCE = "LinstorSnapshotTestSource.img";
    private static final String DUMMY_LINSTOR_TEST_TARGET = "LinstorSnapshotTestTarget.img";

    private static final String FORMAT_VLM_TO_ID_BASE = "%s%s_%05d";
    private static final String FORMAT_VLM_TO_ID = FORMAT_VLM_TO_ID_BASE + ".img";
    private static final String FORMAT_SNAP_VLM_TO_ID = FORMAT_VLM_TO_ID_BASE + "_%s.img";

    private static final String FORMAT_ID_WIPE_IN_PROGRESS = "%s_linstor_wiping_in_progress";

    private static final String LODEV_FILE = "/var/lib/linstor/loop_device_mapping";
    private static final String LODEV_FILE_TMP = LODEV_FILE + ".tmp";

    private static final Map<String, String> LOSETUP_DEVICES = new TreeMap<>();

    protected FileProvider(
        ErrorReporter errorReporter,
        ExtCmdFactory extCmdFactory,
        AccessContext storDriverAccCtx,
        StltConfigAccessor stltConfigAccessor,
        WipeHandler wipeHandler,
        Provider<NotificationListener> notificationListenerProvider,
        Provider<TransactionMgr> transMgrProvider,
        String subTypeDescr,
        DeviceProviderKind subTypeKind
    )
    {
        super(
            errorReporter,
            extCmdFactory,
            storDriverAccCtx,
            stltConfigAccessor,
            wipeHandler,
            notificationListenerProvider,
            transMgrProvider,
            subTypeDescr,
            subTypeKind
        );
    }

    @Inject
    public FileProvider(
        ErrorReporter errorReporter,
        ExtCmdFactory extCmdFactory,
        @DeviceManagerContext AccessContext storDriverAccCtx,
        StltConfigAccessor stltConfigAccessor,
        WipeHandler wipeHandler,
        Provider<NotificationListener> notificationListenerProvider,
        Provider<TransactionMgr> transMgrProvider
    )
    {
        super(
            errorReporter,
            extCmdFactory,
            storDriverAccCtx,
            stltConfigAccessor,
            wipeHandler,
            notificationListenerProvider,
            transMgrProvider,
            "FILE",
            DeviceProviderKind.FILE
        );
    }

    @Override
    protected void updateStates(List<FileData> fileDataList, Collection<SnapshotVolume> snapshots)
        throws StorageException, AccessDeniedException, DatabaseException
    {
        for (FileData fileData : fileDataList)
        {
            final FileInfo fileInfo = infoListCache.get(getFullQualifiedIdentifier(fileData));
            updateInfo(fileData, fileInfo);

            if (fileInfo != null)
            {
                final long expectedSize = fileData.getExepectedSize();
                final long actualSize = fileInfo.size;
                if (actualSize != expectedSize)
                {
                    if (actualSize < expectedSize)
                    {
                        fileData.setSizeState(Size.TOO_SMALL);
                    }
                    else
                    {
                        fileData.setSizeState(Size.TOO_LARGE);
                    }
                }
                else
                {
                    fileData.setSizeState(Size.AS_EXPECTED);
                }
            }
        }
        updateSnapshotStates(snapshots);
    }

    private String getFullQualifiedIdentifier(FileData fileData)
    {
        Path storageDirectory = fileData.getStorageDirectory();
        if (storageDirectory == null)
        {
            String storDirStr;
            try
            {
                storDirStr = DeviceLayerUtils.getNamespaceStorDriver(
                    fileData.getStorPool().getProps(storDriverAccCtx)
                )
                    .getProp(StorageConstants.CONFIG_FILE_DIRECTORY_KEY);
            }
            catch (AccessDeniedException | InvalidKeyException exc)
            {
                throw new ImplementationError(exc);
            }
            storageDirectory = Paths.get(storDirStr);
            fileData.setStorageDirectory(storageDirectory);
        }
        return storageDirectory.resolve(asLvIdentifier(fileData)).toString();
    }

    /*
     * Might be overridden (extended) by future *Providers
     */
    protected void updateInfo(FileData fileData, FileInfo info)
        throws DatabaseException, StorageException
    {
        fileData.setIdentifier(asLvIdentifier(fileData));
        if (info == null)
        {
            fileData.setExists(false);
            fileData.setStorageDirectory(extractStorageDirectory(fileData));
            fileData.setDevicePath(null);
            fileData.setAllocatedSize(-1);
            fileData.setUsableSize(-1);
        }
        else
        {
            fileData.setExists(true);
            fileData.setStorageDirectory(info.directory);
            fileData.setDevicePath(info.loPath.toString());
            fileData.setIdentifier(info.identifier);
            fileData.setAllocatedSize(info.size);
            fileData.setUsableSize(info.size);
        }
    }

    protected Path extractStorageDirectory(FileData fileData) throws StorageException
    {
        return getStorageDirectory(fileData.getStorPool());
    }

    /*
     * Expected to be overridden by LvmThinProvider
     */
    @SuppressWarnings("unused")
    protected void updateSnapshotStates(Collection<SnapshotVolume> snapshots)
        throws AccessDeniedException, DatabaseException
    {
        // no-op
    }

    @Override
    protected void createLvImpl(FileData fileData)
        throws StorageException, AccessDeniedException, DatabaseException
    {
        Path backingFile = fileData.getStorageDirectory().resolve(fileData.getIdentifier());
        FileCommands.createFat(
            extCmdFactory.create(),
            backingFile,
            fileData.getExepectedSize()
        );
        createLoopDevice(fileData, backingFile);
    }

    protected void createLoopDevice(FileData fileData, Path backingFile)
        throws StorageException, DatabaseException
    {
        String loDev = new String(
            LosetupCommands.attach(
                extCmdFactory.create(),
                backingFile
            )
            .stdoutData).trim();

        LOSETUP_DEVICES.put(loDev, backingFile.toString());
        fileData.setDevicePath(loDev);
    }

    @Override
    protected void resizeLvImpl(FileData fileData)
        throws StorageException, AccessDeniedException
    {
        // no special command for resize, just "re-allocate" to the needed size
        FileCommands.createFat(
            extCmdFactory.create(),
            fileData.getStorageDirectory().resolve(fileData.getIdentifier()),
            fileData.getExepectedSize()
        );
        LosetupCommands.resize(
            extCmdFactory.create(),
            fileData.getDevicePath()
        );
    }

    @Override
    protected void deleteLvImpl(FileData fileData, String oldId)
        throws StorageException, DatabaseException
    {
        String devicePath = fileData.getDevicePath();

        Path storageDirectory = fileData.getStorageDirectory();
        // just make sure to not colide with any other ongoing wipe-lv-name
        String newId = String.format(FORMAT_ID_WIPE_IN_PROGRESS, UUID.randomUUID().toString());
        FileCommands.rename(
            storageDirectory,
            oldId,
            newId
        );

        wipeHandler.asyncWipe(
            devicePath,
            ignored ->
            {
                try
                {
                    LosetupCommands.detach(extCmdFactory.create(), devicePath);
                    FileCommands.delete(
                        storageDirectory,
                        newId
                    );
                    fileData.setExists(false);
                }
                catch (DatabaseException exc)
                {
                    throw new ImplementationError(exc);
                }
                catch (StorageException exc)
                {
                    errorReporter.reportError(exc);
                }
            }
        );
        LOSETUP_DEVICES.remove(devicePath);
    }

    @Override
    protected void createSnapshot(FileData fileData, SnapshotVolume snapVlmRef)
        throws StorageException, AccessDeniedException, DatabaseException
    {
        Path storageDirectory = fileData.getStorageDirectory();
        FileCommands.createSnapshot(
            extCmdFactory.create(),
            storageDirectory.resolve(fileData.getIdentifier()),
            storageDirectory.resolve(
                asFullQualifiedPath(
                    fileData.getRscLayerObject().getResourceNameSuffix(),
                    snapVlmRef
                )
            )
        );
    }

    @Override
    protected void restoreSnapshot(String sourceLvIdRef, String sourceSnapNameRef, FileData fileData)
        throws StorageException, AccessDeniedException, DatabaseException
    {
        // sourceLvIdRef is something like "rsc_00000.img"
        // sourceSnapNameRef is something like "snap"
        // we need to concatenate sourceLvIdRef and sourceSnapNameRef with the result of
        // something like "rsc_00000_snap.img"

        String snapId = sourceLvIdRef
            .substring(0, sourceLvIdRef.length() - 4) // cuts the trailing ".img"
            + "_" + sourceSnapNameRef + ".img";

        Path storageDirectory = fileData.getStorageDirectory();
        Path devPath = storageDirectory.resolve(fileData.getIdentifier());
        FileCommands.copy(
            extCmdFactory.create(),
            storageDirectory.resolve(
               snapId
            ),
            devPath
        );
        createLoopDevice(fileData, devPath);
    }

    @Override
    protected void deleteSnapshot(String rscNameSuffixRef, SnapshotVolume snapVlmRef)
        throws StorageException, AccessDeniedException, DatabaseException
    {
        FileCommands.delete(
            getStorageDirectory(snapVlmRef.getStorPool(storDriverAccCtx)),
            asSnapshotFileName(
                snapVlmRef.getResourceName().displayValue,
                rscNameSuffixRef,
                snapVlmRef.getVolumeNumber().value,
                snapVlmRef.getSnapshotName().displayValue
            )
        );
    }

    @Override
    protected boolean snapshotExists(SnapshotVolume snapVlmRef)
        throws StorageException, AccessDeniedException, DatabaseException
    {
        return Files.exists(asFullQualifiedPath("", snapVlmRef));
    }

    @Override
    protected void rollbackImpl(FileData fileData, String rollbackTargetSnapshotNameRef)
        throws StorageException, AccessDeniedException, DatabaseException
    {
        Path storageDirectory = fileData.getStorageDirectory();
        Path snapPath = getSnapVlmPath(
            fileData.getStorageDirectory(),
            fileData.getRscLayerObject().getResourceName().displayValue,
            fileData.getRscLayerObject().getResourceNameSuffix(),
            fileData.getVlmNr().value,
            rollbackTargetSnapshotNameRef
        );
        // do not use java.nio.file.Files.copy ! that seems to do something different (and does not work
        // for this method :) )
        FileCommands.copy(
            extCmdFactory.create(),
            snapPath,
            storageDirectory.resolve(fileData.getIdentifier())
        );
    }

    private Path getSnapVlmPath (
        Path storageDirectoryRef,
        String rscName,
        String rscSuffix,
        int vlmNr,
        String snapName
    )
    {
        return storageDirectoryRef.resolve(asSnapshotFileName(rscName, rscSuffix, vlmNr, snapName));
    }

    private String asSnapshotFileName(String rscName, String rscSuffix, int vlmNr, String snapName)
    {
        return String.format(
            FORMAT_SNAP_VLM_TO_ID,
            rscName,
            rscSuffix,
            vlmNr,
            snapName
        );
    }

    private Path asFullQualifiedPath(String rscNameSuffix, SnapshotVolume snapVlm)
        throws AccessDeniedException, StorageException
    {
        StorPool storPool = snapVlm.getStorPool(storDriverAccCtx);
        return getSnapVlmPath(
            getStorageDirectory(storPool),
            snapVlm.getResourceName().displayValue,
            rscNameSuffix,
            snapVlm.getVolumeNumber().value,
            snapVlm.getSnapshotName().displayValue
        );
    }

    @Override
    protected Map<String, Long> getFreeSpacesImpl() throws StorageException
    {
        Map<String, Long> freeSizes = FileUtils.getDirFreeSizes(changedStoragePoolStrings);
        for (String dir : changedStoragePoolStrings)
        {
            if (!freeSizes.containsKey(dir))
            {
                freeSizes.put(dir, SIZE_OF_NOT_FOUND_STOR_POOL);
            }
        }
        return freeSizes;
    }

    @Override
    protected Map<String, FileInfo> getInfoListImpl(
        List<FileData> fileDataList,
        List<SnapshotVolume> snapVlms
    )
        throws StorageException, AccessDeniedException, DatabaseException
    {
        // It is possible that the backing file still exists for a logical volume, but the loop-device does not
        Map<String, FileData> backingFileToFileDataMap = new HashMap<>();
        for (FileData fileData : fileDataList)
        {
            backingFileToFileDataMap.put(
                getFullQualifiedIdentifier(fileData),
                fileData
            );
        }

        Map<String, FileInfo> infoList = FileUtils.getInfoList(extCmdFactory.create());
        for (Entry<String, FileInfo> entry : infoList.entrySet())
        {
            String backingFile = entry.getKey();
            backingFileToFileDataMap.remove(backingFile);
            LOSETUP_DEVICES.put(entry.getValue().loPath.toString(), backingFile);
        }

        for (Entry<String, FileData> entry : backingFileToFileDataMap.entrySet())
        {
            FileData fileData = entry.getValue();
            Path backingFile = Paths.get(entry.getKey());

            if (Files.exists(backingFile))
            {
                createLoopDevice(fileData, backingFile);
                infoList.put(
                    backingFile.toString(),
                    new FileInfo(
                        Paths.get(fileData.getDevicePath()),
                        backingFile
                    )
                );
            }
        }

        return infoList;
    }

    @Override
    protected String getDevicePath(String storageName, String lvId)
    {
        return null; // we cannot construct it from the given data. however, the devicePath is already
        // set on the corresponding FileData object
    }

    @Override
    protected String asLvIdentifier(ResourceName resourceName, String rscNameSuffix, VolumeNumber volumeNumber)
    {
        return String.format(
            FORMAT_VLM_TO_ID,
            resourceName.displayValue,
            rscNameSuffix,
            volumeNumber.value
        );
    }

    @Override
    protected String getStorageName(StorPool storPoolRef) throws StorageException
    {
        return getStorageDirectory(storPoolRef).toString();
    }

    protected Path getStorageDirectory(StorPool storPool) throws StorageException
    {
        Path dir;
        try
        {
            String dirStr = DeviceLayerUtils.getNamespaceStorDriver(
                storPool.getProps(storDriverAccCtx)
            )
                .getProp(StorageConstants.CONFIG_FILE_DIRECTORY_KEY);
            if (dirStr != null)
            {
                dir = Paths.get(dirStr);
            }
            else
            {
                throw new StorageException("Unset storage directory for " + storPool);
            }
        }
        catch (InvalidKeyException | AccessDeniedException exc)
        {
            throw new ImplementationError(exc);
        }
        return dir;
    }

    @Override
    protected boolean updateDmStats()
    {
        return false;
    }

    @Override
    public long getPoolCapacity(StorPool storPool) throws StorageException, AccessDeniedException
    {
        return FileUtils.getPoolCapacity(extCmdFactory.create(), getStorageDirectory(storPool));
    }

    @Override
    public long getPoolFreeSpace(StorPool storPool) throws StorageException, AccessDeniedException
    {
        return FileUtils.getFreeSpace(extCmdFactory.create(), getStorageDirectory(storPool));
    }

    @Override
    public void checkConfig(StorPool storPool) throws StorageException, AccessDeniedException
    {
        Props props = DeviceLayerUtils.getNamespaceStorDriver(
            storPool.getProps(storDriverAccCtx)
        );
        StorageConfigReader.checkFileStorageDirectoryEntry(props);

        // try to create a dummy snapshot to verify if we can
        StorPoolData storPoolData = (StorPoolData) storPool;
        if (!storPoolData.isSnapshotSupportedInitialized(storDriverAccCtx))
        {
            try
            {
                String dirStr = props.getProp(StorageConstants.CONFIG_FILE_DIRECTORY_KEY);
                Path storageDirectory = Paths.get(dirStr);
                Path dummyVlmPath = storageDirectory.resolve(DUMMY_LINSTOR_TEST_SOURCE);
                Path dummyTargetPath = storageDirectory.resolve(Paths.get(DUMMY_LINSTOR_TEST_TARGET));
                try
                {
                    dummyVlmPath.toFile().createNewFile();
                }
                catch (IOException exc)
                {
                    throw new StorageException("Cannot create dummy file in given storage directory '" + dummyVlmPath + "'", exc);
                }

                try
                {
                    FileCommands.createSnapshot(
                        extCmdFactory.create(),
                        dummyVlmPath,
                        dummyTargetPath
                    );
                    storPoolData.setSupportsSnapshot(storDriverAccCtx, true);
                }
                catch (StorageException ignored)
                {
                    storPoolData.setSupportsSnapshot(storDriverAccCtx, false);
                }
                finally
                {
                    if (Files.exists(dummyTargetPath))
                    {
                        dummyTargetPath.toFile().delete();
                    }
                    if (Files.exists(dummyVlmPath))
                    {
                        dummyVlmPath.toFile().delete();
                    }
                }
            }
            catch (DatabaseException| InvalidKeyException exc)
            {
                throw new ImplementationError(exc);
            }
        }
    }

    @Override
    public void clearCache() throws StorageException
    {
        StringBuilder sb = new StringBuilder();
        for (Entry<String, String> entry : LOSETUP_DEVICES.entrySet())
        {
            sb.append(entry.getKey()).append(":").append(entry.getValue()).append("\n");
        }

        File tmp = new File(LODEV_FILE_TMP);
        try (FileOutputStream fos = new FileOutputStream(tmp))
        {
            fos.write(sb.toString().getBytes());
        }
        catch (IOException exc)
        {
            throw new StorageException("Failed to write active loopback devices to " + LODEV_FILE_TMP, exc);
        }

        try
        {
            Files.move(
                tmp.toPath(),
                new File(LODEV_FILE).toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            );
        }
        catch (IOException exc)
        {
            throw new StorageException("Failed to move " + LODEV_FILE_TMP + " to " + LODEV_FILE, exc);
        }

        super.clearCache();
    }

    @Override
    protected void setDevicePath(FileData vlmData, String devPath) throws DatabaseException
    {
        // ignored - devicePath is set when creating or when looking for fileData objects
    }

    @Override
    protected void setAllocatedSize(FileData vlmData, long size) throws DatabaseException
    {
        vlmData.setAllocatedSize(size);
    }

    @Override
    protected void setUsableSize(FileData vlmData, long size) throws DatabaseException
    {
        vlmData.setUsableSize(size);
    }

    @Override
    protected void setExpectedUsableSize(FileData vlmData, long size)
    {
        vlmData.setExepectedSize(size);
    }

    @Override
    protected String getStorageName(FileData vlmDataRef) throws DatabaseException
    {
        return vlmDataRef.getStorageDirectory().toString();
    }
}
