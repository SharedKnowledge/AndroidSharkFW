package net.sharksystem.android.protocols.routing.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.vividsolutions.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;

public class CoordinateContentProvider {

    private MySQLiteHelper dbHelper;
    private String[] allColumns = { MySQLiteHelper.COLUMN_ID, MySQLiteHelper.COLUMN_LATITUDE, MySQLiteHelper.COLUMN_LONGITUDE };

    public CoordinateContentProvider(Context context) {
        dbHelper = MySQLiteHelper.getInstance(context);
    }

    public CoordinateDTO persist(Coordinate coordinate) {
        SQLiteDatabase database = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(MySQLiteHelper.COLUMN_LATITUDE, coordinate.x);
        values.put(MySQLiteHelper.COLUMN_LONGITUDE, coordinate.y);

        long insertId = database.insert(MySQLiteHelper.TABLE_COORDINATES, null, values);
        Cursor cursor = database.query(MySQLiteHelper.TABLE_COORDINATES, allColumns, MySQLiteHelper.COLUMN_ID + " = " + insertId, null, null, null, null);
        cursor.moveToFirst();
        CoordinateDTO newCoordinateDTO = cursorToCoordinate(cursor);
        cursor.close();

        dbHelper.close();
        return newCoordinateDTO;
    }

    public void deleteCoordinate(CoordinateDTO coordinateDTO) {
        SQLiteDatabase database = dbHelper.getWritableDatabase();
        long id = coordinateDTO.getId();
        System.out.println("Coordinate deleted with id: " + id);
        database.delete(MySQLiteHelper.TABLE_COORDINATES, MySQLiteHelper.COLUMN_ID + " = " + id, null);
        dbHelper.close();
    }

    public List<CoordinateDTO> getAllCoordinates() {
        SQLiteDatabase database = dbHelper.getReadableDatabase();
        List<CoordinateDTO> coordinateDTOs = new ArrayList<CoordinateDTO>();

        Cursor cursor = database.query(MySQLiteHelper.TABLE_COORDINATES,  allColumns, null, null, null, null, null);

        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            CoordinateDTO coordinateDTO = cursorToCoordinate(cursor);
            coordinateDTOs.add(coordinateDTO);
            cursor.moveToNext();
        }
        // make sure to close the cursor
        cursor.close();
        dbHelper.close();
        return coordinateDTOs;
    }

    private CoordinateDTO cursorToCoordinate(Cursor cursor) {
        CoordinateDTO coordinateDTO = new CoordinateDTO();
        coordinateDTO.setId(cursor.getLong(0));
        coordinateDTO.setLatitude(cursor.getFloat(1));
        coordinateDTO.setLongitude(cursor.getFloat(2));
        return coordinateDTO;
    }
}

