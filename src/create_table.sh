#!/usr/bin/env bash
# Small script to setup the tables used by OpenTSDB.

TSDB_TABLE=${TSDB_TABLE:-'/monitoring/tsdb'}
UID_TABLE=${UID_TABLE:-'/monitoring/tsdb-uid'}
TREE_TABLE=${TREE_TABLE:-'/monitoring/tsdb-tree'}
META_TABLE=${META_TABLE:-'/monitoring/tsdb-meta'}
LOGFILE=${OT_HOME}/var/log/opentsdb/opentsdb_create_table_$$.log
# Create monitoring volume before creating tables
maprcli volume create -name monitoring -path /monitoring > $LOGFILE 2>&1
RC0=$?
if [ $RC0 -ne 0 ]; then
  echo "Create volume failed for /monitoring"
  return $RC0 2> /dev/null || exit $RC0
fi
for t in $TSDB_TABLE $UID_TABLE $TREE_TABLE $META_TABLE; do
    maprcli table info -path $t > $LOGFILE 2>&1
    RC1=$?
    if [ $RC1 -ne 0 ]; then
        echo "Creating $t table..."
        maprcli table create -path $t -defaultreadperm p -defaultwriteperm p -defaultappendperm p >> $LOGFILE 2>&1
        RC2=$?
        if [ $RC2 -ne 0 ]; then
            # check if another node beat us too it
            if ! tail -1 $LOGFILE | fgrep $t | fgrep 'File exists' > /dev/null 2>&1 ; then
                echo "Create table failed for $t"
                return $RC2 2> /dev/null || exit $RC2
            else
                continue
            fi
        fi
        COLUMN_FLAG="false"
        if [ "$t" == "$UID_TABLE" ]; then
            OT_COLUMNS="id name"
            COLUMN_FLAG="true"
            elif [ "$t" == "$META_TABLE" ]; then
            OT_COLUMNS="name"
        else
            OT_COLUMNS="t"
        fi
        for columnFamily in $OT_COLUMNS ; do
            echo "Creating CF $columnFamily for Table $t"
            maprcli table cf create -path $t -cfname $columnFamily -maxversions 1 -inmemory $COLUMN_FLAG -compression lzf -ttl 0 >> $LOGFILE 2>&1
            RC2=$?
            if [ $RC2 -ne 0 ]; then
                echo "Create CF $columnFamily failed for table $t"
                return $RC2 2> /dev/null || exit $RC2
            fi
        done
    else
        echo "$t exists."
    fi
done
echo "Complete!"

true

