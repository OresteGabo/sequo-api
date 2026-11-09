package dev.orestegabo.sequo_api.domain.testaccess

import com.fasterxml.jackson.databind.ObjectMapper
import dev.orestegabo.sequo_api.domain.auth.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties=[
    "spring.datasource.url=jdbc:h2:mem:test_account_access;DB_CLOSE_DELAY=-1", "sequo.auth.test-accounts.enabled=true"])
class TestAccountAccessHttpTest @Autowired constructor(private val jdbc:JdbcTemplate,
    private val credentials:TestAccountCredentialRepository,private val users:UserRepository,private val auth:AuthService) {
    @LocalServerPort private var port=0
    private val json=ObjectMapper();private val http=HttpClient.newHttpClient()
    private val password="fixture-password-only-123456"
    data class Account(val username:String,val id:String,val family:String,val device:String,val scope:String)
    private fun seed(family:String):Account {
        val suffix=UUID.randomUUID().toString().replace(Regex("[^a-z]"),"").take(10)
        val (id,role,scope)=when(family){
            "VENDOR" -> Triple("demo-v1-shop-login$suffix-owner","MERCHANT_OWNER","demo-v1-shop-login$suffix")
            "HUB" -> Triple("live-test-v1-hub-$suffix-operator","RELAY_PARTNER","live-test-v1-hub-$suffix")
            "CUSTOMER" -> Triple("live-test-v1-customer","CUSTOMER","")
            else -> Triple("demo-v1-rider-4-user","COURIER","demo-v1-rider-4")
        }
        if(!users.existsById(id)) {
            jdbc.update("insert into users(id,email,display_name,is_active,provider,status,created_at,updated_at) values (?,?,?,true,'EMAIL','ACTIVE',current_timestamp,current_timestamp)",id,"$id@fixture.invalid","[TEST] Login")
            jdbc.update("insert into user_roles(user_id,role_code) values (?,?)",id,role)
            when(family){
                "VENDOR" -> {jdbc.update("insert into merchants(id,owner_user_id,name,status,commission_rate_bps,created_at,updated_at,version) values (?,?,?,'ACTIVE',1500,current_timestamp,current_timestamp,0)",scope,id,"[TEST] Shop")
                    jdbc.update("insert into merchant_memberships(id,user_id,merchant_id,role_code,active,created_at,updated_at) values (?,?,?,'MERCHANT_OWNER',true,current_timestamp,current_timestamp)","membership-$suffix",id,scope)}
                "HUB" -> jdbc.update("insert into relay_points(id,operator_user_id,name,status,created_at,updated_at,version) values (?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",scope,id,"[TEST] Hub")
                "CUSTOMER" -> Unit
                else -> jdbc.update("insert into couriers(id,user_id,status,created_at,updated_at,version) values (?,?,'ACTIVE',current_timestamp,current_timestamp,0)",scope,id)
            }
        }
        val a=Account("account-$suffix",id,family,"device-$suffix",scope)
        credentials.saveAndFlush(TestAccountCredential(a.username,id,family,TestAccountPassword.hash(password),expiresAt=Instant.now().plusSeconds(3600)))
        return a
    }
    private fun call(path:String,body:Any?=null,token:String?=null):HttpResponse<String> {
        val b=HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/$path")).header("Content-Type","application/json")
        if(token!=null)b.header("Authorization","Bearer $token")
        if(body==null)b.GET() else b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString())
    }
    private fun login(a:Account,secret:String=password,family:String=a.family,source:String=when(a.family){"RIDER"->"SEQUO_RIDER";"CUSTOMER"->"SEQUO_APP";else->"SEQUO_HUB"})=call("auth/test-accounts/session",mapOf("username" to a.username,"password" to secret,"appFamily" to family,"device" to mapOf("deviceId" to a.device,"appSource" to source)))
    @Test fun vendorGetsOnlyItsShopAndUsesNormalDeviceSession() {
        val a=seed("VENDOR");val response=login(a);assertEquals(200,response.statusCode())
        val tokens=json.readTree(response.body());val access=tokens["accessToken"].asText();val refresh=tokens["refreshToken"].asText()
        val me=json.readTree(call("auth/me",token=access).body())
        assertEquals(a.id,me["id"].asText());assertEquals(setOf(a.scope),me["merchantScopeIds"].map{it.asText()}.toSet())
        assertEquals(401,call("auth/refresh",mapOf("refreshToken" to refresh,"deviceId" to "wrong")).statusCode())
        val rotated=call("auth/refresh",mapOf("refreshToken" to refresh,"deviceId" to a.device));assertEquals(200,rotated.statusCode())
        assertEquals(401,call("auth/refresh",mapOf("refreshToken" to refresh,"deviceId" to a.device)).statusCode())
    }
    @Test fun dedicatedTestCustomerCannotUseOperationalApps() {
        val a=seed("CUSTOMER");val response=login(a);assertEquals(200,response.statusCode())
        val me=json.readTree(call("auth/me",token=json.readTree(response.body())["accessToken"].asText()).body())
        assertEquals(setOf("CUSTOMER"),me["roles"].map{it.asText()}.toSet())
        assertEquals(401,login(a,family="VENDOR",source="SEQUO_HUB").statusCode())
    }
    @Test fun rolesAndCredentialFamilyCannotCrossApps() {
        for(family in listOf("HUB","RIDER")) {
            val a=seed(family);val response=login(a);assertEquals(200,response.statusCode())
            val me=json.readTree(call("auth/me",token=json.readTree(response.body())["accessToken"].asText()).body())
            assertEquals(a.id,me["id"].asText());assertEquals(401,login(a,family="VENDOR").statusCode())
            assertEquals(401,login(a,source="SEQUO_APP").statusCode())
        }
    }
    @Test fun wrongExpiredDisabledAndElevatedAccountsAreRefused() {
        val a=seed("VENDOR")
        assertEquals(401,login(a,secret="incorrect-password-123456").statusCode())
        jdbc.update("update test_account_credentials set expires_at=? where username=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),a.username)
        assertEquals(401,login(a).statusCode())
        jdbc.update("update test_account_credentials set expires_at=?,enabled=false where username=?",java.sql.Timestamp.from(Instant.now().plusSeconds(3600)),a.username)
        assertEquals(401,login(a).statusCode())
        jdbc.update("update test_account_credentials set enabled=true where username=?",a.username)
        jdbc.update("insert into user_roles(user_id,role_code) values (?,'SUPER_ADMIN')",a.id)
        assertEquals(401,login(a).statusCode())
    }
    @Test fun revokedMembershipAndOrdinaryIdentityCannotUseTestPassword() {
        val a=seed("VENDOR")
        jdbc.update("update merchant_memberships set active=false where user_id=?",a.id)
        assertEquals(401,login(a).statusCode())
        jdbc.update("update merchant_memberships set active=true where user_id=?",a.id)
        jdbc.update("update users set email='ordinary@example.org' where id=?",a.id)
        assertEquals(401,login(a).statusCode())
    }
    @Test fun additionalRealShopOrHubScopePreventsTestLogin() {
        val a=seed("VENDOR")
        jdbc.update("insert into merchants(id,name,status,commission_rate_bps,created_at,updated_at,version) values ('ordinary-shop','Ordinary','ACTIVE',1500,current_timestamp,current_timestamp,0)")
        jdbc.update("insert into merchant_memberships(id,user_id,merchant_id,role_code,active,created_at,updated_at) values ('extra-membership',?,'ordinary-shop','MERCHANT_OWNER',true,current_timestamp,current_timestamp)",a.id)
        assertEquals(401,login(a).statusCode())
        val b=seed("HUB")
        jdbc.update("insert into relay_points(id,operator_user_id,name,status,created_at,updated_at,version) values ('ordinary-hub',?,'Ordinary','ACTIVE',current_timestamp,current_timestamp,0)",b.id)
        assertEquals(401,login(b).statusCode())
    }
    @Test fun passwordAttemptsAreRateLimited() {
        val a=seed("VENDOR")
        repeat(10){assertEquals(401,login(a,secret="incorrect-password-123456").statusCode())}
        assertEquals(429,login(a).statusCode())
    }
    @Test fun disabledFeatureCannotMintSessions() {
        val off=TestAccountAccessService(false,credentials,users,auth,jdbc)
        assertNull(off.login("none",password,"VENDOR",DeviceBinding("device",AppSource.SEQUO_HUB,null)))
    }
    @Test fun passwordsAreSaltedAndComparedWithoutPlaintextStorage() {
        val a=TestAccountPassword.hash(password);val b=TestAccountPassword.hash(password)
        assertNotEquals(a,b);assertFalse(a.contains(password));assertTrue(TestAccountPassword.matches(password,a))
        assertFalse(TestAccountPassword.matches("wrong",a));assertFalse(TestAccountPassword.matches(password,"bad"))
    }
}
