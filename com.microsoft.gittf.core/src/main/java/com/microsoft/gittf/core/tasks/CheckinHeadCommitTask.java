/***********************************************************************************************
 * Copyright (c) Microsoft Corporation All rights reserved.
 * 
 * MIT License:
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ***********************************************************************************************/

package com.microsoft.gittf.core.tasks;

import java.io.File;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import com.microsoft.gittf.core.GitTFConstants;
import com.microsoft.gittf.core.Messages;
import com.microsoft.gittf.core.config.ChangesetCommitMap;
import com.microsoft.gittf.core.config.GitTFConfiguration;
import com.microsoft.gittf.core.tasks.framework.NullTaskProgressMonitor;
import com.microsoft.gittf.core.tasks.framework.TaskExecutor;
import com.microsoft.gittf.core.tasks.framework.TaskProgressDisplay;
import com.microsoft.gittf.core.tasks.framework.TaskProgressMonitor;
import com.microsoft.gittf.core.tasks.framework.TaskStatus;
import com.microsoft.gittf.core.util.ChangesetCommitUtil;
import com.microsoft.gittf.core.util.ChangesetCommitUtil.ChangesetCommitDetails;
import com.microsoft.gittf.core.util.Check;
import com.microsoft.gittf.core.util.CommitUtil;
import com.microsoft.gittf.core.util.CommitWalker;
import com.microsoft.gittf.core.util.CommitWalker.CommitDelta;
import com.microsoft.gittf.core.util.RepositoryUtil;
import com.microsoft.tfs.core.clients.versioncontrol.VersionControlClient;
import com.microsoft.tfs.core.clients.versioncontrol.path.ServerPath;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.DeletedState;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.Item;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.ItemType;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.RecursionType;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.WorkItemCheckinInfo;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.Workspace;
import com.microsoft.tfs.core.clients.versioncontrol.specs.version.LatestVersionSpec;
import com.microsoft.tfs.util.FileHelpers;

public class CheckinHeadCommitTask
    extends WorkspaceTask
{
    public static final int ALREADY_UP_TO_DATE = 1;

    private static final Log log = LogFactory.getLog(CheckinHeadCommitTask.class);

    private final Repository repository;
    private final String serverPath;

    private boolean deep = false;

    private AbbreviatedObjectId[] squashCommitIDs = new AbbreviatedObjectId[0];
    private WorkItemCheckinInfo[] workItems;
    private boolean lock = true;
    private boolean overrideGatedCheckin;
    private boolean autoSquashMultipleParents;

    public CheckinHeadCommitTask(
        final Repository repository,
        final VersionControlClient versionControlClient,
        final String serverPath)
    {
        super(repository, versionControlClient, serverPath);

        Check.notNull(repository, "repository"); //$NON-NLS-1$
        Check.notNullOrEmpty(serverPath, "serverPath"); //$NON-NLS-1$

        this.repository = repository;
        this.serverPath = serverPath;
    }

    public boolean getDeep()
    {
        return deep;
    }

    public void setDeep(final boolean deep)
    {
        this.deep = deep;
    }

    public AbbreviatedObjectId[] getSquashCommitIDs()
    {
        return squashCommitIDs;
    }

    public void setSquashCommitIDs(AbbreviatedObjectId[] squashCommitIDs)
    {
        this.squashCommitIDs = (squashCommitIDs == null) ? new AbbreviatedObjectId[0] : squashCommitIDs;
    }

    public WorkItemCheckinInfo[] getWorkItemCheckinInfo()
    {
        return workItems;
    }

    public void setWorkItemCheckinInfo(WorkItemCheckinInfo[] workItems)
    {
        this.workItems = workItems;
    }

    public boolean getOverrideGatedCheckin()
    {
        return this.overrideGatedCheckin;
    }

    public void setOverrideGatedCheckin(boolean overrideGatedCheckin)
    {
        this.overrideGatedCheckin = overrideGatedCheckin;
    }

    public boolean getLock()
    {
        return this.lock;
    }

    public void setLock(boolean lock)
    {
        this.lock = lock;
    }

    public boolean getAutoSquash()
    {
        return this.autoSquashMultipleParents;
    }

    public void setAutoSquash(boolean autoSquashMultipleParents)
    {
        this.autoSquashMultipleParents = autoSquashMultipleParents;
    }

    @Override
    public TaskStatus run(final TaskProgressMonitor progressMonitor)
    {
        GitTFWorkspaceData workspaceData = null;

        progressMonitor.beginTask(
            Messages.formatString(
                "CheckinHeadCommitTask.CheckingInToPathFormat", GitTFConfiguration.loadFrom(repository).getServerPath()), 1, //$NON-NLS-1$
            TaskProgressDisplay.DISPLAY_PROGRESS.combine(TaskProgressDisplay.DISPLAY_SUBTASK_DETAIL));

        try
        {
            /*
             * Create a temporary workspace
             */

            final Workspace workspace;
            final File workingFolder;

            workspaceData = createWorkspace(progressMonitor.newSubTask(1));

            workspace = workspaceData.getWorkspace();
            workingFolder = workspaceData.getWorkingFolder();

            if (lock)
            {
                final TaskStatus lockStatus =
                    new TaskExecutor(progressMonitor.newSubTask(1)).execute(new LockTask(workspace, serverPath));

                if (!lockStatus.isOK())
                {
                    return lockStatus;
                }
            }

            /*
             * Walk the repo
             */
            ObjectId headCommitID = RepositoryUtil.getHeadCommitID(repository);

            final ChangesetCommitMap commitMap = new ChangesetCommitMap(repository);

            final ChangesetCommitDetails lastBridgedChangeset = ChangesetCommitUtil.getLastBridgedChangeset(commitMap);
            final ChangesetCommitDetails latestChangeset =
                ChangesetCommitUtil.getLatestChangeset(commitMap, workspace, serverPath);

            /*
             * This is a repository that has been configured and never checked
             * in to tfs before. We need to validate that the path in tfs either
             * does not exist or is empty
             */
            if (lastBridgedChangeset == null || lastBridgedChangeset.getChangesetID() < 0)
            {
                Item[] items =
                    workspace.getClient().getItems(
                        serverPath,
                        LatestVersionSpec.INSTANCE,
                        RecursionType.FULL,
                        DeletedState.ANY,
                        ItemType.ANY).getItems();

                if (items != null && items.length > 0)
                {
                    /* The folder can exist but has to be empty */
                    if (!(items.length == 1 && ServerPath.equals(items[0].getServerItem(), serverPath)))
                    {
                        return new TaskStatus(TaskStatus.ERROR, Messages.formatString(
                            "CheckinHeadCommitTask.CannotCheckinToANonEmptyFolderFormat", serverPath)); //$NON-NLS-1$
                    }
                }
            }

            /*
             * There is a changeset for this path on the server, but it does not
             * have a corresponding commit in the map. The user must merge with
             * the latest changeset.
             */
            else if (latestChangeset != null && latestChangeset.getCommitID() == null)
            {
                return new TaskStatus(TaskStatus.ERROR, Messages.formatString(
                    "CheckinHeadCommitTask.NotFastForwardFormat", //$NON-NLS-1$
                    Integer.toString(latestChangeset.getChangesetID())));
            }

            /*
             * The server path does not exist, but we have previously downloaded
             * some items from it, thus it has been deleted. We cannot proceed.
             */
            else if (latestChangeset == null && lastBridgedChangeset != null)
            {
                return new TaskStatus(TaskStatus.ERROR, Messages.formatString(
                    "CheckinHeadCommitTask.ServerPathDoesNotExistFormat", //$NON-NLS-1$
                    serverPath));
            }

            /*
             * The current HEAD is the latest changeset on the TFS server.
             * Nothing to do.
             */
            else if (latestChangeset != null && latestChangeset.getCommitID().equals(headCommitID))
            {
                return new TaskStatus(TaskStatus.OK, CheckinHeadCommitTask.ALREADY_UP_TO_DATE);
            }

            progressMonitor.setDetail(Messages.getString("CheckinHeadCommitTask.ExaminingRepository")); //$NON-NLS-1$
            List<CommitDelta> commitsToCheckin =
                getCommitsToCheckin(latestChangeset != null ? latestChangeset.getCommitID() : null, headCommitID);
            progressMonitor.setDetail(null);

            int lastChangesetID = -1;
            ObjectId lastCommitID = null;

            boolean anyThingCheckedIn = false;

            progressMonitor.setWork(commitsToCheckin.size() * 2);

            for (int i = 0; i < commitsToCheckin.size(); i++)
            {
                CommitDelta commitDelta = commitsToCheckin.get(i);
                boolean isLastCommit = (i == (commitsToCheckin.size() - 1));

                progressMonitor.setDetail(Messages.formatString("CheckinHeadCommitTask.CommitFormat", //$NON-NLS-1$
                    CommitUtil.abbreviate(repository, commitDelta.getToCommit())));

                /* Save space: clean working folder after each checkin */
                if (i > 0)
                {
                    cleanWorkingFolder(workingFolder);
                }

                final PendDifferenceTask pendTask =
                    new PendDifferenceTask(
                        repository,
                        commitDelta.getFromCommit(),
                        commitDelta.getToCommit(),
                        workspace,
                        serverPath,
                        workingFolder);

                final TaskStatus pendStatus = new TaskExecutor(progressMonitor.newSubTask(1)).execute(pendTask);

                if (!pendStatus.isOK())
                {
                    return pendStatus;
                }

                if (pendStatus.getCode() == PendDifferenceTask.NOTHING_TO_PEND)
                {
                    continue;
                }

                anyThingCheckedIn = true;

                final CheckinPendingChangesTask checkinTask =
                    new CheckinPendingChangesTask(
                        repository,
                        commitDelta.getToCommit(),
                        workspace,
                        pendTask.getPendingChanges());

                if (isLastCommit)
                {
                    checkinTask.setWorkItemCheckinInfo(workItems);
                }

                checkinTask.setOverrideGatedCheckin(overrideGatedCheckin);

                progressMonitor.setDetail(Messages.getString("CheckinHeadCommitTask.CheckingIn")); //$NON-NLS-1$

                final TaskStatus checkinStatus = new TaskExecutor(progressMonitor.newSubTask(1)).execute(checkinTask);

                if (!checkinStatus.isOK())
                {
                    return checkinStatus;
                }

                lastChangesetID = checkinTask.getChangesetID();
                lastCommitID = commitDelta.getToCommit();

                progressMonitor.displayVerbose(Messages.formatString("CheckinHeadCommitTask.CheckedInChangesetFormat", //$NON-NLS-1$
                    CommitUtil.abbreviate(repository, lastCommitID),
                    Integer.toString(checkinTask.getChangesetID())));
            }

            final TaskProgressMonitor cleanupMonitor = progressMonitor.newSubTask(1);
            cleanupWorkspace(cleanupMonitor, workspaceData);
            workspaceData = null;

            progressMonitor.endTask();

            // There was nothing detected to checkin.
            if (!anyThingCheckedIn)
            {
                return new TaskStatus(TaskStatus.OK, CheckinHeadCommitTask.ALREADY_UP_TO_DATE);
            }

            if (commitsToCheckin.size() == 1)
            {
                progressMonitor.displayMessage(Messages.formatString(
                    "CheckinHeadCommitTask.CheckedInFormat", CommitUtil.abbreviate(repository, lastCommitID), Integer.toString(lastChangesetID))); //$NON-NLS-1$
            }
            else
            {
                progressMonitor.displayMessage(Messages.formatString(
                    "CheckinHeadCommitTask.CheckedInMultipleFormat", Integer.toString(commitsToCheckin.size()), Integer.toString(lastChangesetID))); //$NON-NLS-1$                
            }

            return TaskStatus.OK_STATUS;
        }
        catch (Exception e)
        {
            return new TaskStatus(TaskStatus.ERROR, e);
        }
        finally
        {
            if (workspaceData != null)
            {
                cleanupWorkspace(new NullTaskProgressMonitor(), workspaceData);
            }
        }
    }

    private final void cleanWorkingFolder(final File workingFolder)
    {
        try
        {
            FileHelpers.deleteDirectory(workingFolder);
            workingFolder.mkdirs();
        }
        catch (Exception e)
        {
            /* Not fatal */
            log.warn(MessageFormat.format("Could not clean up temporary directory {0}", //$NON-NLS-1$
                workingFolder.getAbsolutePath()), e);
        }
    }

    private final List<CommitDelta> getCommitsToCheckin(
        final ObjectId latestChangesetCommitID,
        final ObjectId headCommitID)
        throws Exception
    {
        Check.notNull(headCommitID, "headCommitID"); //$NON-NLS-1$

        List<CommitDelta> commitsToCheckin;

        /*
         * In the case of shallow commit, we do not care if the user provided
         * ids to squash or not since we are not preserving history anyways we
         * select any path we find and that would be ok
         */
        if (autoSquashMultipleParents || !deep)
        {
            commitsToCheckin =
                CommitWalker.getAutoSquashedCommitList(repository, latestChangesetCommitID, headCommitID);
        }
        else
        {
            commitsToCheckin =
                CommitWalker.getCommitList(repository, latestChangesetCommitID, headCommitID, squashCommitIDs);
        }

        int depth = deep ? Integer.MAX_VALUE : GitTFConstants.GIT_TF_SHALLOW_DEPTH;

        /* Prune the list of commits down to their depth. */
        if (commitsToCheckin.size() > depth)
        {
            List<CommitDelta> prunedCommits = new ArrayList<CommitDelta>();

            RevCommit lastToCommit = null;
            RevCommit lastFromCommit = null;

            for (int i = 0; i < depth - 1; i++)
            {
                CommitDelta delta = commitsToCheckin.get(commitsToCheckin.size() - 1 - i);

                prunedCommits.add(delta);

                lastToCommit = delta.getFromCommit();
            }

            lastFromCommit = commitsToCheckin.get(0).getFromCommit();

            if (lastToCommit == null)
            {
                lastToCommit = commitsToCheckin.get(commitsToCheckin.size() - 1).getToCommit();
            }

            Check.notNull(lastToCommit, "lastToCommit"); //$NON-NLS-1$
            Check.notNull(lastFromCommit, "lastFromCommit"); //$NON-NLS-1$

            prunedCommits.add(new CommitDelta(lastFromCommit, lastToCommit));

            commitsToCheckin = prunedCommits;
        }

        return commitsToCheckin;
    }

    private void cleanupWorkspace(final TaskProgressMonitor progressMonitor, final GitTFWorkspaceData workspaceData)
    {
        if (workspaceData == null)
        {
            return;
        }

        final Workspace workspace = workspaceData.getWorkspace();

        progressMonitor.beginTask(Messages.getString("CheckinHeadCommitTask.DeletingWorkspace"), //$NON-NLS-1$
            TaskProgressMonitor.INDETERMINATE,
            TaskProgressDisplay.DISPLAY_PROGRESS);

        if (workspaceData.getWorkspace() != null && lock)
        {
            final TaskStatus unlockStatus =
                new TaskExecutor(progressMonitor.newSubTask(1)).execute(new UnlockTask(workspace, serverPath));

            if (!unlockStatus.isOK())
            {
                log.warn(MessageFormat.format("Could not unlock {0}: {1}", serverPath, unlockStatus.getMessage())); //$NON-NLS-1$                
            }
        }

        disposeWorkspace(progressMonitor.newSubTask(1));

        progressMonitor.endTask();
    }
}
