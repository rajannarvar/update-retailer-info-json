package com.narvar.tools;

import com.amazonaws.util.json.JSONException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.postgresql.util.PGobject;

/**
 * This class main method carries out the copying of the 4 JSON keys:
 * 1. "products"
 * 2. "locale"
 * 3. "retailer_type"
 * 4. "carriers"
 * along with their corresponding values from the "retailer_info_settings_json" column 
 * to the "retailer_info_json" column of the retailer_info table
 * 
 */
public class UpdateRetailerInfoJson {

    private static final Connection POSTGRES_CONNECTION;
    private static final String PRODUCTS_JSON_KEY = "products";
    private static final String LOCALE_JSON_KEY = "locale";
    private static final String RETAILER_TYPE_JSON_KEY = "retailer_type";
    private static final String CARRIERS_JSON_KEY = "carriers";

    static {
        POSTGRES_CONNECTION = PostgresConnection.getPostgresConnection();
    }

    public static void main(String args[]) throws IOException, JSONException {

        PreparedStatement st;
        ResultSet rs;
        Long retailerId;
        String uriMoniker;
        String retailerInfoSettingsJson;
        String retailerInfoJson;
        try {
            st = POSTGRES_CONNECTION.prepareStatement("SELECT dbio_rowid, uri_moniker, retailer_info_settings_json, retailer_info_json from retailer_info ri");
            rs = st.executeQuery();

//            st = POSTGRES_CONNECTION.prepareStatement("SELECT dbio_rowid, uri_moniker, retailer_info_settings_json, retailer_info_json from retailer_info ri where ri.dbio_rowid = ?");
//            st.setInt(1, 4653);
//            rs = st.executeQuery();
//
//            st = POSTGRES_CONNECTION.prepareStatement("SELECT dbio_rowid, uri_moniker, retailer_info_settings_json, retailer_info_json from retailer_info ri where ri.dbio_rowid in (?, ?)");
//            st.setInt(1, 4653);
//            st.setInt(2, 133);
//            rs = st.executeQuery();
            
            int counterOfUpdatedRows = 0;
            Set<Long> updatedJsonForRetailerIds = new TreeSet<>();
            while (rs.next()) {
                retailerId = rs.getLong(1);
                uriMoniker = rs.getString(2);
                retailerInfoSettingsJson = rs.getString(3);
                retailerInfoJson = rs.getString(4);
                Map<String, JsonNode> fourPairsToAppend = extractTheFourPairsFromJSON(retailerInfoSettingsJson);
                Map<String, JsonNode> retailerInfoJsonMap = retrieveMapRepresentationForJSON(retailerInfoJson);
                String updatedRetailerJsonString = updateRetailerJsonStringWithTokensMap(fourPairsToAppend, retailerInfoJsonMap);
//                System.out.println("updatedRetailerJsonString= " + updatedRetailerJsonString);
                if (!Objects.equals(updatedRetailerJsonString, retailerInfoJson)) {
                    saveUpdates(updatedRetailerJsonString, retailerId);
                    counterOfUpdatedRows++;
                    updatedJsonForRetailerIds.add(retailerId);
                }
            }
            System.out.println("------------------------> No errors");
            System.out.println("------------------------> Number of updated rows= " + counterOfUpdatedRows);
            System.out.println("A list of retailers IDs whom updated retailer_info_json column: ");
            String collected = updatedJsonForRetailerIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(" - ", "List starts: ", " - List ends"));
            System.out.println(collected);
        } catch (SQLException ex) {
            System.out.println("------------------------> " + ex);
        }
    }


    /**
     * Refer https://stackoverflow.com/a/35845278/1725975
     * @param updatedRetailerJsonString
     * @param retailerId
     * @throws SQLException 
     */
    private static void saveUpdates(String updatedRetailerJsonString, Long retailerId) throws SQLException {
        PGobject jsonObject = new PGobject();
        jsonObject.setType("json");
        jsonObject.setValue(updatedRetailerJsonString);
        PreparedStatement st = POSTGRES_CONNECTION.prepareStatement("UPDATE retailer_info SET retailer_info_json = ? WHERE dbio_rowid = ?");
        st.setObject(1, jsonObject);
        st.setLong(2, retailerId);
        st.execute();
    }

    private static Map<String, JsonNode> extractTheFourPairsFromJSON(String retailerInfoSettingsJson) throws IOException, SQLException, JSONException {
        Map<String, JsonNode> tokenNameToAccessToken = new HashMap<>();
        if (retailerInfoSettingsJson != null && !retailerInfoSettingsJson.isEmpty()) {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonFactory factory = objectMapper.getFactory();
            JsonParser jsonParser = factory.createParser(retailerInfoSettingsJson);
            JsonNode jsonTree = objectMapper.readTree(jsonParser);
            Iterator<Map.Entry<String, JsonNode>> accessTokenEntries = jsonTree.fields();
            while (accessTokenEntries.hasNext()) {
                Map.Entry<String, JsonNode> tokenEntry = accessTokenEntries.next();
                final String tokenName = tokenEntry.getKey();
                if (tokenName.contentEquals(CARRIERS_JSON_KEY) || tokenName.contentEquals(RETAILER_TYPE_JSON_KEY) || tokenName.contentEquals(LOCALE_JSON_KEY) || tokenName.contentEquals(PRODUCTS_JSON_KEY)){
//                    jsonTree.path(API_CONFIG_JSON_KEY).
                    tokenNameToAccessToken.put(tokenName, tokenEntry.getValue());
                }
//                else do notheing
            }
        }
        return tokenNameToAccessToken;
    }

    private static Map<String, JsonNode> retrieveMapRepresentationForJSON(String retailerInfoJson) throws IOException, SQLException, JSONException {
        Map<String, JsonNode> tokenNameToAccessToken = new HashMap<>();
        if (retailerInfoJson != null && !retailerInfoJson.isEmpty()) {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonFactory factory = objectMapper.getFactory();
            JsonParser jp = factory.createParser(retailerInfoJson);
            JsonNode jsonTree = objectMapper.readTree(jp);
            Iterator<Map.Entry<String, JsonNode>> accessTokenEntries = jsonTree.fields();
            while (accessTokenEntries.hasNext()) {
                Map.Entry<String, JsonNode> tokenEntry = accessTokenEntries.next();
                final String tokenName = tokenEntry.getKey();
                JsonNode jsonNode = tokenEntry.getValue();
                tokenNameToAccessToken.put(tokenName, jsonNode);
            }
        }
        return tokenNameToAccessToken;
    }

    private static String updateRetailerJsonStringWithTokensMap(Map<String, JsonNode> tokenNameValuePair, Map<String, JsonNode> outerMap) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        if (outerMap == null) {
            outerMap = new HashMap<>();
        } 
        outerMap.putAll(tokenNameValuePair);
        String retailerInfoJsonString = objectMapper.writeValueAsString(outerMap);
        return retailerInfoJsonString;
    }

}
