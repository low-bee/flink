/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.test.checkpointing;

import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.contrib.streaming.state.EmbeddedRocksDBStateBackend;
import org.apache.flink.core.execution.RestoreMode;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.runtime.checkpoint.OperatorState;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.checkpoint.metadata.CheckpointMetadata;
import org.apache.flink.runtime.state.IncrementalRemoteKeyedStateHandle;
import org.apache.flink.runtime.state.KeyedStateHandle;
import org.apache.flink.runtime.state.filemerging.SegmentFileStateHandle;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.test.util.MiniClusterWithClientResource;
import org.apache.flink.test.util.TestUtils;
import org.apache.flink.util.TernaryBoolean;
import org.apache.flink.util.TestLogger;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.apache.flink.runtime.state.filesystem.AbstractFsCheckpointStorageAccess.CHECKPOINT_SHARED_STATE_DIR;
import static org.apache.flink.runtime.state.filesystem.AbstractFsCheckpointStorageAccess.CHECKPOINT_TASK_OWNED_STATE_DIR;
import static org.apache.flink.test.checkpointing.ResumeCheckpointManuallyITCase.runJobAndGetExternalizedCheckpoint;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FileMerging Compatibility IT case which tests recovery from a checkpoint created in different
 * fileMerging mode (i.e. fileMerging enabled/disabled).
 */
public class SnapshotFileMergingCompatibilityITCase extends TestLogger {

    private static final long DELETE_TIMEOUT_MILLS = 60000;

    public static Collection<Object[]> parameters() {
        return Arrays.asList(
                new Object[][] {
                    {RestoreMode.CLAIM, true},
                    {RestoreMode.CLAIM, false},
                    {RestoreMode.NO_CLAIM, true},
                    {RestoreMode.NO_CLAIM, false}
                });
    }

    @ParameterizedTest(name = "RestoreMode = {0}, fileMergingAcrossBoundary = {1}")
    @MethodSource("parameters")
    public void testSwitchFromDisablingToEnablingFileMerging(
            RestoreMode restoreMode, boolean fileMergingAcrossBoundary, @TempDir Path checkpointDir)
            throws Exception {
        testSwitchingFileMerging(
                checkpointDir, false, true, restoreMode, fileMergingAcrossBoundary);
    }

    @ParameterizedTest(name = "RestoreMode = {0}, fileMergingAcrossBoundary = {1}")
    @MethodSource("parameters")
    public void testSwitchFromEnablingToDisablingFileMerging(
            RestoreMode restoreMode, boolean fileMergingAcrossBoundary, @TempDir Path checkpointDir)
            throws Exception {
        testSwitchingFileMerging(
                checkpointDir, true, false, restoreMode, fileMergingAcrossBoundary);
    }

    private void testSwitchingFileMerging(
            Path checkpointDir,
            boolean firstFileMergingSwitch,
            boolean secondFileMergingSwitch,
            RestoreMode restoreMode,
            boolean fileMergingAcrossBoundary)
            throws Exception {
        final Configuration config = new Configuration();
        // Wait for 4 checkpoints each round to subsume the original one and produce the
        // PlaceholderStreamStateHandle in the final round
        final int consecutiveCheckpoint = 4;
        config.set(CheckpointingOptions.CHECKPOINTS_DIRECTORY, checkpointDir.toUri().toString());
        config.set(CheckpointingOptions.INCREMENTAL_CHECKPOINTS, true);
        config.set(CheckpointingOptions.FILE_MERGING_ACROSS_BOUNDARY, fileMergingAcrossBoundary);
        config.set(CheckpointingOptions.FILE_MERGING_ENABLED, firstFileMergingSwitch);
        MiniClusterWithClientResource firstCluster =
                new MiniClusterWithClientResource(
                        new MiniClusterResourceConfiguration.Builder()
                                .setConfiguration(config)
                                .setNumberTaskManagers(2)
                                .setNumberSlotsPerTaskManager(2)
                                .build());
        EmbeddedRocksDBStateBackend stateBackend1 = new EmbeddedRocksDBStateBackend();
        stateBackend1.configure(config, Thread.currentThread().getContextClassLoader());
        firstCluster.before();
        String firstCheckpoint;
        try {
            firstCheckpoint =
                    runJobAndGetExternalizedCheckpoint(
                            stateBackend1,
                            null,
                            firstCluster,
                            restoreMode,
                            config,
                            consecutiveCheckpoint,
                            true);
            assertThat(firstCheckpoint).isNotNull();
            verifyStateHandleType(firstCheckpoint, firstFileMergingSwitch);
        } finally {
            firstCluster.after();
        }

        config.set(CheckpointingOptions.FILE_MERGING_ENABLED, secondFileMergingSwitch);
        EmbeddedRocksDBStateBackend stateBackend2 = new EmbeddedRocksDBStateBackend();
        stateBackend2.configure(config, Thread.currentThread().getContextClassLoader());
        MiniClusterWithClientResource secondCluster =
                new MiniClusterWithClientResource(
                        new MiniClusterResourceConfiguration.Builder()
                                .setConfiguration(config)
                                .setNumberTaskManagers(2)
                                .setNumberSlotsPerTaskManager(2)
                                .build());
        secondCluster.before();
        String secondCheckpoint;
        try {
            secondCheckpoint =
                    runJobAndGetExternalizedCheckpoint(
                            stateBackend2,
                            firstCheckpoint,
                            secondCluster,
                            restoreMode,
                            config,
                            consecutiveCheckpoint,
                            true);
            assertThat(secondCheckpoint).isNotNull();
            verifyStateHandleType(secondCheckpoint, secondFileMergingSwitch);
            verifyCheckpointExistOrWaitDeleted(
                    firstCheckpoint,
                    determineFileExist(
                            restoreMode, firstFileMergingSwitch, secondFileMergingSwitch),
                    firstFileMergingSwitch);
        } finally {
            secondCluster.after();
        }

        EmbeddedRocksDBStateBackend stateBackend3 = new EmbeddedRocksDBStateBackend();
        stateBackend3.configure(config, Thread.currentThread().getContextClassLoader());
        MiniClusterWithClientResource thirdCluster =
                new MiniClusterWithClientResource(
                        new MiniClusterResourceConfiguration.Builder()
                                .setConfiguration(config)
                                .setNumberTaskManagers(3)
                                .setNumberSlotsPerTaskManager(2)
                                .build());
        thirdCluster.before();
        String thirdCheckpoint;
        try {
            thirdCheckpoint =
                    runJobAndGetExternalizedCheckpoint(
                            stateBackend3,
                            secondCheckpoint,
                            thirdCluster,
                            restoreMode,
                            config,
                            consecutiveCheckpoint,
                            true);
            assertThat(thirdCheckpoint).isNotNull();
            verifyStateHandleType(thirdCheckpoint, secondFileMergingSwitch);
            verifyCheckpointExistOrWaitDeleted(
                    secondCheckpoint,
                    determineFileExist(
                            restoreMode, secondFileMergingSwitch, secondFileMergingSwitch),
                    secondFileMergingSwitch);
        } finally {
            thirdCluster.after();
        }

        // We config ExternalizedCheckpointRetention.DELETE_ON_CANCELLATION here.
        EmbeddedRocksDBStateBackend stateBackend4 = new EmbeddedRocksDBStateBackend();
        stateBackend4.configure(config, Thread.currentThread().getContextClassLoader());
        MiniClusterWithClientResource fourthCluster =
                new MiniClusterWithClientResource(
                        new MiniClusterResourceConfiguration.Builder()
                                .setConfiguration(config)
                                .setNumberTaskManagers(3)
                                .setNumberSlotsPerTaskManager(2)
                                .build());
        fourthCluster.before();
        String fourthCheckpoint;
        try {
            fourthCheckpoint =
                    runJobAndGetExternalizedCheckpoint(
                            stateBackend4,
                            thirdCheckpoint,
                            fourthCluster,
                            restoreMode,
                            config,
                            consecutiveCheckpoint,
                            false);
            assertThat(fourthCheckpoint).isNotNull();
            verifyCheckpointExistOrWaitDeleted(
                    thirdCheckpoint,
                    determineFileExist(
                            restoreMode, secondFileMergingSwitch, secondFileMergingSwitch),
                    secondFileMergingSwitch);
            verifyCheckpointExistOrWaitDeleted(
                    fourthCheckpoint, TernaryBoolean.FALSE, secondFileMergingSwitch);
        } finally {
            fourthCluster.after();
        }
    }

    private void verifyStateHandleType(String checkpointPath, boolean fileMergingEnabled)
            throws IOException {
        CheckpointMetadata metadata = TestUtils.loadCheckpointMetadata(checkpointPath);
        boolean hasKeyedState = false;
        for (OperatorState operatorState : metadata.getOperatorStates()) {
            for (OperatorSubtaskState subtaskState : operatorState.getStates()) {
                // Check keyed state handle
                List<KeyedStateHandle> keyedStateHandles =
                        new ArrayList<>(subtaskState.getManagedKeyedState());
                for (KeyedStateHandle keyedStateHandle : keyedStateHandles) {
                    assertThat(keyedStateHandle)
                            .isInstanceOf(IncrementalRemoteKeyedStateHandle.class);
                    ((IncrementalRemoteKeyedStateHandle) keyedStateHandle)
                            .streamSubHandles()
                            .forEach(
                                    handle -> {
                                        if (fileMergingEnabled) {
                                            assertThat(handle)
                                                    .isInstanceOf(SegmentFileStateHandle.class);
                                        } else {
                                            assertThat(handle)
                                                    .isNotInstanceOf(SegmentFileStateHandle.class);
                                        }
                                    });
                    hasKeyedState = true;
                }
            }
        }
        assertThat(hasKeyedState).isTrue();
    }

    private static TernaryBoolean determineFileExist(
            RestoreMode mode, boolean lastFileMergingEnabled, boolean thisFileMergingEnabled) {
        if (mode == RestoreMode.CLAIM) {
            if (lastFileMergingEnabled || thisFileMergingEnabled) {
                // file merging will not reference files from previous jobs.
                return TernaryBoolean.FALSE;
            } else {
                return TernaryBoolean.UNDEFINED;
            }
        } else {
            return TernaryBoolean.TRUE;
        }
    }

    private static void verifyCheckpointExistOrWaitDeleted(
            String checkpointPath, TernaryBoolean exist, boolean fileMergingEnabled)
            throws Exception {
        org.apache.flink.core.fs.Path checkpointDir =
                new org.apache.flink.core.fs.Path(checkpointPath);
        FileSystem fs = checkpointDir.getFileSystem();
        org.apache.flink.core.fs.Path baseDir = checkpointDir.getParent();
        org.apache.flink.core.fs.Path sharedFile =
                new org.apache.flink.core.fs.Path(baseDir, CHECKPOINT_SHARED_STATE_DIR);
        org.apache.flink.core.fs.Path taskOwnedFile =
                new org.apache.flink.core.fs.Path(baseDir, CHECKPOINT_TASK_OWNED_STATE_DIR);
        assertThat(fs.exists(baseDir)).isTrue();
        assertThat(fs.exists(sharedFile)).isTrue();
        assertThat(fs.exists(taskOwnedFile)).isTrue();
        if (exist.equals(TernaryBoolean.TRUE)) {
            // should exist, just check
            assertThat(fs.exists(checkpointDir)).isTrue();
            assertThat(fs.listStatus(sharedFile) != null && fs.listStatus(sharedFile).length > 0)
                    .isTrue();
            // Since there is no exclusive state, we should consider fileMergingEnabled.
            assertThat(
                            fs.listStatus(taskOwnedFile) != null
                                    && fs.listStatus(taskOwnedFile).length > 0)
                    .isEqualTo(fileMergingEnabled);
        } else if (exist.equals(TernaryBoolean.FALSE)) {
            // should be cleaned, since the job io threads may work slow, we wait.
            long waited = 0L;
            boolean fileExist = true;
            while (fileExist) {
                try {
                    fileExist =
                            (fs.exists(checkpointDir)
                                    || (fs.listStatus(sharedFile) != null
                                            && fs.listStatus(sharedFile).length > 0)
                                    || (fs.listStatus(taskOwnedFile) != null
                                            && fs.listStatus(taskOwnedFile).length > 0));
                } catch (IOException e) {
                    // Sometimes it may happen that the files are being deleted while we list them,
                    // thus an IOException is raised.
                }
                if (fileExist) {
                    // We wait
                    Thread.sleep(500L);
                    waited += 500L;
                    // Or timeout
                    assertThat(waited).isLessThan(DELETE_TIMEOUT_MILLS);
                }
            }
        }
    }
}
