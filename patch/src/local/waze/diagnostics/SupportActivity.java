// SPDX-License-Identifier: GPL-3.0-only
package local.waze.diagnostics;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import local.waze.cluster.ClusterStatus;

/** Framework-only English support screen; exports only validated report bodies through SAF. */
public final class SupportActivity extends Activity {
    private Diagnostics d;
    private LinearLayout layout, reports;
    private CheckBox automatic;
    private TextView status,clusterStatus;
    private Spinner category,cluster,hud,stock;
    private String exportBody;
    private boolean busy,updating,resumed;
    private long captureUntil;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){if(resumed){load();handler.postDelayed(this,5000);}}};
    private final Runnable clusterRefresh=new Runnable(){public void run(){if(resumed){clusterStatus.setText(ClusterStatus.describe(SupportActivity.this));handler.postDelayed(this,1000);}}};
    public void onCreate(Bundle saved) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(saved);
        d=Diagnostics.get(this);
        if(saved!=null){exportBody=saved.getString("export");captureUntil=saved.getLong("capture_until",0);}
        ScrollView scroll=new ScrollView(this);layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(16*getResources().getDisplayMetrics().density);layout.setPadding(pad,pad,pad,pad);scroll.addView(layout);setContentView(scroll);
        text("Destek · Waze for BYD",24);
        text("Sürüm "+ReportSchema.BUILD+" · Destek ve Tanılama\nTeknik raporlar diagnostics.localcharge.app adresine iletilir. Sınırlı bir özet GitHub Issues üzerinde görünebilir. Kuyruğa alınması gönderildiği anlamına gelmez ve başarılı bir yönlendirme çağrısı HUD'da bir şey göründüğünü kanıtlamaz.",16);
        clusterSection();
        automatic=new CheckBox(this);automatic.setText("Tanılamaları otomatik gönder");layout.addView(automatic);
        text("Devam edildikten sonra, sunucu desteği doğrulandığında ölçülen entegrasyon hataları otomatik olarak gönderilebilir (24 saatte en fazla bir yeni rapor). Başarılı oturumlar yüklenmez. Bu seçeneği kapatmak otomatik yeniden denemeleri durdurur ve gönderilmemiş raporları iptal eder.",16);
        automatic.setOnCheckedChangeListener((v,on) -> {if(!updating && d!=null){d.choiceChanged(on);d.execute(() -> {d.store.saveChoice(on,false);d.schedule();},this::load,() -> message("Tanılama tercihi kaydedilemedi."));}});
        category=select("Sorun kategorisi",new String[]{"HUD","Gösterge haritası","Direksiyon tuşları","Navigasyon","Diğer"});
        cluster=select("Gösterge yönlendirmesi görüldü mü",new String[]{"Bilinmiyor","Evet","Hayır"});
        hud=select("HUD yönlendirmesi görüldü mü",new String[]{"Bilinmiyor","Evet","Hayır"});
        stock=select("Orijinal navigasyon HUD'da görüldü mü",new String[]{"Bilinmiyor","Evet","Hayır"});
        text("Bu üç gözlem birbirinden bağımsızdır. Bizzat kontrol etmediyseniz Bilinmiyor olarak bırakın. Raporlar rotaları, koordinatları, sokak adlarını, hesap bilgilerini ve ekran görüntülerini İÇERMEZ.",16);
        button("Tanılamayı Gönder",() -> draft(false));
        button("Tanılamayı Dışa Aktar",() -> draft(true));
        button("Tanılama oturumu kaydet (60 saniye)",() -> {captureUntil=System.currentTimeMillis()+60000;load();});
        status=text("Destek durumu yükleniyor…",16);
        reports=new LinearLayout(this);reports.setOrientation(LinearLayout.VERTICAL);layout.addView(reports);
        button("Geri",this::finish);
        if(d==null)message("Tanılama depolama alanı kullanılamıyor.");
    }
    private void clusterSection(){
        text("Gösterge Paneli (Cluster)",20);
        text("Gösterge ayarları, Waze haritasının sağ üstündeki Gösterge Paneli menüsündedir. Aşağıdaki durum bilgisi bu cihazda kalır ve asla yüklenmez.",14);
        clusterStatus=text("Gösterge durumu yükleniyor…",14);clusterStatus.setTextIsSelectable(true);
    }
    private TextView text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setPadding(0,8,0,8);layout.addView(v);return v;}
    private void button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v -> action.run());layout.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    private Spinner select(String label,String[] options){text(label,16);Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));layout.addView(s);return s;}
    private static final String[] OBSERVED_VALUES={"unknown","yes","no"};
    private String value(Spinner s){int pos=s.getSelectedItemPosition();return pos>=0&&pos<OBSERVED_VALUES.length?OBSERVED_VALUES[pos]:"unknown";}
    private void message(String s){if(!isFinishing()&&!isDestroyed())Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    protected void onResume(){super.onResume();resumed=true;refresh.run();clusterRefresh.run();}
    protected void onPause(){resumed=false;handler.removeCallbacks(refresh);handler.removeCallbacks(clusterRefresh);super.onPause();}
    protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("export",exportBody);b.putLong("capture_until",captureUntil);}
    void showAutomaticChoice(boolean selected){updating=true;automatic.setChecked(selected);updating=false;}
    private void load(){
        if(d==null||busy)return;
        d.execute(() -> {
            boolean selected=d.store.automaticChoice();JSONArray rows=new JSONArray();
            for(int i=0;i<d.store.queue.length();i++) {
                JSONObject r=d.store.queue.getJSONObject(i);
                rows.put(ReportSchema.obj("key",r.getString("key"),"state",r.getString("state"),"issue",r.optString("issue"),"cancel",r.optBoolean("cancel"),"automatic",r.optBoolean("automatic"),"export",r.has("body")));
            }
            d.schedule();
            d.main.post(() -> {if(!resumed||isDestroyed())return;showAutomaticChoice(selected);showReports(rows);});
        },() -> {},() -> {if(resumed)status.setText("Diagnostics storage is unavailable. No report was sent.");});
    }
    private void draft(boolean export){
        if(d==null||busy)return;
        if(captureUntil>System.currentTimeMillis()){message("Recording technical events. Preview will be available after 60 seconds.");return;}
        if(captureUntil>0 && System.currentTimeMillis()-captureUntil>=600000){captureUntil=0;message("Kaydedilen oturumun süresi doldu. Tekrar kaydedin veya son tanılamaları gönderin.");return;}
        busy=true;
        String ca=ReportSchema.CATEGORIES[Math.max(0,Math.min(4,category.getSelectedItemPosition()))],cl=value(cluster),hu=value(hud),st=value(stock);
        d.execute(() -> {
            long now=System.currentTimeMillis();
            String body=captureUntil==0?d.store.report(ca,cl,hu,st,now):d.store.reportWindow(ca,cl,hu,st,now,captureUntil-60000,captureUntil,true);
            d.main.post(() -> {busy=false;if(isFinishing()||isDestroyed())return;if(export)export(body);else preview(body);});
        },() -> {},() -> {busy=false;message("Doğrulanmış bir rapor oluşturulamadı. Hiçbir şey gönderilmedi.");});
    }
    private void preview(String body){
        ScrollView scroll=new ScrollView(this);TextView preview=new TextView(this);preview.setTextIsSelectable(true);
        String pretty;try{pretty=new JSONObject(body).toString(2);}catch(Exception ignored){return;}
        preview.setText("Gönder'e basıldığında bu rapor diagnostics.localcharge.app adresine yüklenir ve GitHub Issues üzerinde sınırlı bir özet yayınlanmasına izin verilir. GitHub hesabı gerekmez.\n\n"+pretty);preview.setPadding(24,16,24,16);scroll.addView(preview);
        new AlertDialog.Builder(this).setTitle("Genel tanılama raporu önizlemesi").setView(scroll)
            .setNegativeButton("İptal",null).setNeutralButton("Dışa Aktar",(x,y) -> export(body))
            .setPositiveButton("Gönder",(x,y) -> {
                if(busy)return;busy=true;
                d.execute(() -> {d.store.enqueue(body,System.currentTimeMillis());d.schedule();},
                    () -> {busy=false;message("Beklemede: Rapor teslimat için kaydedildi.");load();},
                    () -> {busy=false;message("Rapor eklenemedi. Kuyruk dolu olabilir veya depolama kullanılamıyor.");});
            }).show();
    }
    private void showReports(JSONArray rows){
        reports.removeAllViews();
        long remaining=captureUntil-System.currentTimeMillis();
        status.setText((!d.schedulingAvailable?"Arka plan zamanlaması kullanılamıyor. Yeniden denemek için Destek'i tekrar açın veya uygulamayı yeniden başlatın.\n":"")+(remaining>0?"Teknik oturum kaydediliyor: "+((remaining+999)/1000)+" saniye kaldı.\n":"")+"Raporlar: "+rows.length()+" / 3. Denemeler 24 saat sonra durur. Dışa aktarma kullanılabilir.");
        for(int i=0;i<rows.length();i++)try {
            JSONObject r=rows.getJSONObject(i);String key=r.getString("key"),state=r.getString("state"),url=r.getString("issue");
            TextView label=new TextView(this);
            String readable;
            switch(state){
                case "pending":readable="Beklemede — sunucu onayı bekleniyor";break;
                case "queued":readable="Kuyrukta — sunucu kabul etti; GitHub teslimatı bekliyor";break;
                case "submitted":readable="Rapor gönderildi — GitHub sorunu onaylandı";break;
                case "delivering":readable="İletiliyor — iptal edilemez";break;
                case "delivery_uncertain":readable="Teslimat belirsiz — sunucu eşitlemesi gerekiyor";break;
                case "expired":readable="Deneme süresi doldu — teslimatın kontrol edilmesi gerekebilir";break;
                case "stopped":readable="Durduruldu — doğrulama veya makbuz hatası; dışa aktarılabilir";break;
                default:readable=state;
            }
            label.setText((r.getBoolean("automatic")?"Otomatik · ":"Manuel · ")+key.substring(0,8)+": "+readable+(r.getBoolean("cancel")?" · İptal istendi":""));reports.addView(label);
            if(ReportEngine.validIssue(url))rowButton("GitHub sorununu aç",() -> {try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){message("Tarayıcı bulunamadı.");}});
            if(r.getBoolean("export"))rowButton("Bu raporu dışa aktar",() -> d.execute(() -> {JSONObject item=d.store.find(key);if(item!=null){String body=item.getString("body");ReportSchema.validate(body);d.main.post(() -> export(body));}},() -> {},() -> message("Dışa aktarma kullanılamıyor.")));
            if(r.getBoolean("export")&&!state.equals("submitted")&&!state.equals("delivering")&&!state.equals("cancelled")&&!state.equals("delivery_uncertain"))rowButton("Bekleyen raporu iptal et",() -> {
                new AlertDialog.Builder(this).setTitle("Bekleyen rapor iptal edilsin mi?")
                    .setMessage("Kabul durumu belirsizse, iptal işleminden önce kontrol edilmelidir. Halihazırda iletilen raporlar geri alınamaz.")
                    .setNegativeButton("Koru",null).setPositiveButton("İptal talebi gönder",(a,b) -> d.execute(() -> {d.engine.cancel(key,System.currentTimeMillis());d.schedule();},this::load,() -> message("İptal işlemi kaydedilemedi."))).show();
            });
            rowButton("Yerel kopyayı sil",() -> new AlertDialog.Builder(this).setTitle("Yerel kopya silinsin mi?")
                .setMessage("Bu işlem kaydedilen raporu bu cihazdan kaldırır. Sunucu teslimatını iptal etmez. Mümkünse önce Bekleyen raporu iptal et seçeneğini kullanın.")
                .setNegativeButton("Koru",null).setPositiveButton("Sil",(a,b) -> d.execute(() -> {d.store.remove(key);d.schedule();},this::load,() -> message("Yerel kopya silinemedi."))).show());
        }catch(Exception ignored){}
    }
    private void rowButton(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v -> action.run());reports.addView(b);}
    private void export(String body){
        if(isFinishing()||isDestroyed())return;
        try{ReportSchema.validate(body);exportBody=body;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"waze-for-byd-diagnostics.json"),47);}
        catch(Exception e){message("Belge seçici bulunamadı. Dışa aktarma için bir Android belge sağlayıcısı gerekir.");}
    }
    protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=47||result!=RESULT_OK||data==null||data.getData()==null||exportBody==null)return;
        final String body=exportBody;exportBody=null;final Uri uri=data.getData();
        d.execute(() -> {ReportSchema.validate(body);try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException();out.write(body.getBytes(StandardCharsets.UTF_8));}},() -> message("Tanılama başarıyla dışa aktarıldı."),() -> message("Dışa aktarma başarısız oldu."));
    }
}
