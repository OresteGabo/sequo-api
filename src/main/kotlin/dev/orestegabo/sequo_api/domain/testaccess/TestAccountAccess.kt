package dev.orestegabo.sequo_api.domain.testaccess

import dev.orestegabo.sequo_api.domain.auth.*
import jakarta.persistence.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.http.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Entity @Table(name="test_account_credentials")
class TestAccountCredential(@Id @Column(length=80) val username:String,
    @Column(name="user_id",nullable=false) val userId:String,
    @Column(name="app_family",nullable=false,length=32) val appFamily:String,
    @Column(name="password_hash",nullable=false,length=500) val passwordHash:String,
    @Column(nullable=false) var enabled:Boolean=true,
    @Column(name="expires_at",nullable=false) val expiresAt:Instant,
    @Column(name="created_at",nullable=false) val createdAt:Instant=Instant.now())
interface TestAccountCredentialRepository:JpaRepository<TestAccountCredential,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TestAccountCredential c where c.username=:username")
    fun lockCredential(username:String):TestAccountCredential?
}

/** Passwords exist only for explicitly provisioned fictional identities, never ordinary accounts. */
object TestAccountPassword {
    private const val iterations=210000
    fun hash(password:String):String {
        val salt=ByteArray(24).also(SecureRandom()::nextBytes)
        return "pbkdf2-sha256:$iterations:${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(derive(password,salt))}"
    }
    fun matches(password:String,hash:String):Boolean=runCatching {
        val parts=hash.split(':');require(parts.size==4 && parts[0]=="pbkdf2-sha256" && parts[1]==iterations.toString())
        val salt=Base64.getDecoder().decode(parts[2]);val expected=Base64.getDecoder().decode(parts[3])
        require(salt.size==24 && expected.size==32)
        MessageDigest.isEqual(expected,derive(password,salt))
    }.getOrDefault(false)
    private fun derive(password:String,salt:ByteArray):ByteArray {
        val spec=PBEKeySpec(password.toCharArray(),salt,iterations,256)
        return try {SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded} finally {spec.clearPassword()}
    }
}

@Service
class TestAccountAccessService(@Value("\${sequo.auth.test-accounts.enabled:false}") val enabled:Boolean,
    private val credentials:TestAccountCredentialRepository,private val users:UserRepository,
    private val auth:AuthService,private val jdbc:JdbcTemplate) {
    private val dummyHash=TestAccountPassword.hash("not-a-login-"+java.util.UUID.randomUUID())
    @Transactional
    fun login(username:String,password:String,family:String,device:DeviceBinding):AuthTokens? {
        if(!enabled || username.length !in 1..80 || password.length !in 12..128 || device.deviceId.length !in 1..128) return null
        val credential=credentials.lockCredential(username.trim().lowercase())
        val matches=TestAccountPassword.matches(password,credential?.passwordHash ?: dummyHash)
        if(credential==null || !matches || !credential.enabled || !credential.expiresAt.isAfter(Instant.now()) || credential.appFamily!=family || device.appSource!=when(family){"CUSTOMER"->AppSource.SEQUO_APP;"VENDOR","HUB"->AppSource.SEQUO_HUB;"RIDER"->AppSource.SEQUO_RIDER;else->return null}) return null
        val user=users.lockCheckoutOwner(credential.userId) ?: return null
        if(!eligibleIdentity(user,family) || !scopeExists(credential.userId,family)) return null
        return auth.issueTokensForExistingUser(credential.userId,user.provider,device)
    }
    private fun scopeExists(id:String,source:String):Boolean {
        if(source=="CUSTOMER") return id=="live-test-v1-customer"
        if(source=="VENDOR") {
            val expected = if(id.endsWith("-owner")) id.removeSuffix("-owner") else
                listOf("bistro","saveurs","grains","marche").getOrNull(id.substringAfterLast('-').toIntOrNull()?.minus(1) ?: -1)?.let { "demo-v1-shop-$it" } ?: return false
            val role=if(id.endsWith("-owner")) "MERCHANT_OWNER" else "MERCHANT_STAFF"
            val memberships=jdbc.queryForList("select mm.merchant_id,mm.role_code,m.status,m.owner_user_id from merchant_memberships mm join merchants m on m.id=mm.merchant_id where mm.user_id=? and mm.active=true",id)
            return memberships.size==1 && memberships.single().let {
                it["merchant_id"]==expected && it["role_code"]==role && it["status"]=="ACTIVE" && (role!="MERCHANT_OWNER" || it["owner_user_id"]==id)
            }
        }
        if(source=="HUB") {
            val points=jdbc.queryForList("select id,status from relay_points where operator_user_id=?",id)
            return points.size==1 && points.single().let {it["id"]==id.removeSuffix("-operator") && it["status"]=="ACTIVE"}
        }
        val query=when(source) {
            "RIDER" -> "select count(*) from couriers where user_id=? and status='ACTIVE' and id in ('demo-v1-rider-1','demo-v1-rider-2','demo-v1-rider-3','demo-v1-rider-4')"
            else -> return false
        }
        return (jdbc.queryForObject(query,Long::class.java,id) ?: 0L)>0
    }
    companion object {
        fun eligibleIdentity(user:User,source:String):Boolean {
            val id=user.id.orEmpty()
            if(!user.active || !user.status.canAuthenticate() || user.provider!=AuthProvider.EMAIL || user.email?.endsWith(".invalid")!=true) return false
            val role=when(source) {
                "CUSTOMER" -> if(id=="live-test-v1-customer") RoleCode.CUSTOMER else return false
                "VENDOR" -> if(id.matches(Regex("demo-v1-shop-[a-z]+-owner"))) RoleCode.MERCHANT_OWNER
                    else if(id.matches(Regex("live-test-v1-vendor-staff-[1-4]"))) RoleCode.MERCHANT_STAFF else return false
                "HUB" -> if(id.matches(Regex("live-test-v1-hub-[a-z-]+-operator"))) RoleCode.RELAY_PARTNER else return false
                "RIDER" -> if(id.matches(Regex("demo-v1-rider-[1-4]-user"))) RoleCode.COURIER else return false
                else -> return false
            }
            return role in user.roles && user.roles.all {it==role || it==RoleCode.CUSTOMER}
        }
    }
}

@RestController @RequestMapping("/api/auth/test-accounts")
class TestAccountAccessController(private val access:TestAccountAccessService,private val limiter:InMemoryRateLimiter) {
    data class Login(val username:String,val password:String,val appFamily:String,val device:AuthController.DeviceRequest)
    @GetMapping fun capability()=ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapOf("enabled" to access.enabled))
    @PostMapping("/session")
    fun login(@RequestBody body:Login,request:HttpServletRequest):ResponseEntity<Any> {
        if(!access.enabled) return ResponseEntity.notFound().build()
        val decisions=listOf(limiter.consume("test-login.ip",request.remoteAddr,RateLimitRule(80,Duration.ofMinutes(10))),
            limiter.consume("test-login.user",body.username.trim().take(80).lowercase().ifBlank{"empty"},RateLimitRule(10,Duration.ofMinutes(10))))
        if(decisions.any{!it.allowed}) return ResponseEntity.status(429).cacheControl(CacheControl.noStore()).header("Retry-After",decisions.maxOf{it.retryAfterSeconds}.toString()).build()
        val tokens=access.login(body.username,body.password,body.appFamily,DeviceBinding(body.device.deviceId,body.device.appSource,null,deviceLabel=body.device.deviceLabel?.take(100)))
        return if(tokens==null) ResponseEntity.status(401).cacheControl(CacheControl.noStore()).body(mapOf("message" to "Test sign-in failed. Check the credentials, app and account expiry."))
            else ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokens)
    }
}
