/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.service.accord.serializers;

import java.io.IOException;

import accord.messages.Accept;
import accord.messages.Accept.AcceptNack;
import accord.messages.Accept.AcceptOk;
import accord.messages.Accept.AcceptReply;
import accord.primitives.PartialRoute;
import accord.primitives.TxnId;
import org.apache.cassandra.db.TypeSizes;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;

import static accord.local.Command.AcceptOutcome.RejectedBallot;
import static accord.messages.Accept.SerializerSupport.create;

public class AcceptSerializers
{
    private AcceptSerializers() {}

    public static final IVersionedSerializer<Accept> request = new TxnRequestSerializer.WithUnsyncedSerializer<Accept>()
    {
        @Override
        public void serializeBody(Accept accept, DataOutputPlus out, int version) throws IOException
        {
            CommandSerializers.ballot.serialize(accept.ballot, out, version);
            CommandSerializers.timestamp.serialize(accept.executeAt, out, version);
            KeySerializers.keys.serialize(accept.keys, out, version);
            DepsSerializer.partialDeps.serialize(accept.partialDeps, out, version);
            CommandSerializers.kind.serialize(accept.kind, out, version);
        }

        @Override
        public Accept deserializeBody(DataInputPlus in, int version, TxnId txnId, PartialRoute scope, long waitForEpoch, long minEpoch, boolean doNotComputeProgressKey) throws IOException
        {
            return create(txnId, scope, waitForEpoch, minEpoch, doNotComputeProgressKey,
                          CommandSerializers.ballot.deserialize(in, version),
                          CommandSerializers.timestamp.deserialize(in, version),
                          KeySerializers.keys.deserialize(in, version),
                          DepsSerializer.partialDeps.deserialize(in, version),
                          CommandSerializers.kind.deserialize(in, version));
        }

        @Override
        public long serializedBodySize(Accept accept, int version)
        {
            return CommandSerializers.ballot.serializedSize(accept.ballot, version)
                   + CommandSerializers.timestamp.serializedSize(accept.executeAt, version)
                   + KeySerializers.keys.serializedSize(accept.keys, version)
                   + DepsSerializer.partialDeps.serializedSize(accept.partialDeps, version)
                   + CommandSerializers.kind.serializedSize(accept.kind, version);
        }
    };

    public static final IVersionedSerializer<Accept.Invalidate> invalidate = new IVersionedSerializer<Accept.Invalidate>()
    {
        @Override
        public void serialize(Accept.Invalidate invalidate, DataOutputPlus out, int version) throws IOException
        {
            CommandSerializers.ballot.serialize(invalidate.ballot, out, version);
            CommandSerializers.txnId.serialize(invalidate.txnId, out, version);
            KeySerializers.routingKey.serialize(invalidate.someKey, out, version);
        }

        @Override
        public Accept.Invalidate deserialize(DataInputPlus in, int version) throws IOException
        {
            return new Accept.Invalidate(CommandSerializers.ballot.deserialize(in, version),
                                         CommandSerializers.txnId.deserialize(in, version),
                                         KeySerializers.routingKey.deserialize(in, version));
        }

        @Override
        public long serializedSize(Accept.Invalidate invalidate, int version)
        {
            return CommandSerializers.ballot.serializedSize(invalidate.ballot, version)
                   + CommandSerializers.txnId.serializedSize(invalidate.txnId, version)
                   + KeySerializers.routingKey.serializedSize(invalidate.someKey, version);
        }
    };

    public static final IVersionedSerializer<AcceptReply> reply = new IVersionedSerializer<AcceptReply>()
    {
        @Override
        public void serialize(AcceptReply reply, DataOutputPlus out, int version) throws IOException
        {
            switch (reply.outcome())
            {
                default: throw new AssertionError();
                case Success:
                    out.writeByte(1);
                    DepsSerializer.partialDeps.serialize(((AcceptOk)reply).deps, out, version);
                    break;
                case Redundant:
                    out.writeByte(2);
                    break;
                case RejectedBallot:
                    out.writeByte(3);
                    CommandSerializers.ballot.serialize(((AcceptNack) reply).supersededBy, out, version);
            }
        }

        @Override
        public AcceptReply deserialize(DataInputPlus in, int version) throws IOException
        {
            int type = in.readByte();
            switch (type)
            {
                default: throw new IllegalStateException("Unexpected AcceptNack type: " + type);
                case 1:
                    return new AcceptOk(DepsSerializer.partialDeps.deserialize(in, version));
                case 2:
                    return AcceptNack.REDUNDANT;
                case 3:
                    return new AcceptNack(RejectedBallot, CommandSerializers.ballot.deserialize(in, version));
            }
        }

        @Override
        public long serializedSize(AcceptReply reply, int version)
        {
            long size = TypeSizes.BYTE_SIZE;
            switch (reply.outcome())
            {
                default: throw new AssertionError();
                case Success:
                    size += DepsSerializer.partialDeps.serializedSize(((AcceptOk)reply).deps, version);
                    break;
                case Redundant:
                    break;
                case RejectedBallot:
                    size += CommandSerializers.ballot.serializedSize(((AcceptNack) reply).supersededBy, version);
            }
            return size;
        }
    };
}