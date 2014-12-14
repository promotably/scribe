# scribe

Consumer application that takes event data off the kinesis stream and inserts them into promotably's analytics database

## Usage

```lein run```

## Environment Variables

RDS_HOST - the hostname of the postgres analytics database
RDS_DB_NAME - the name of the database
RDS_PORT - the port for the database connection
RDS_USER - the user for connecting to the database
RDS_PW - the user's password for connecting to the database
KINESIS_A - the kinesis stream name scribe should consume events on
