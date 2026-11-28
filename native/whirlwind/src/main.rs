use anyhow::{bail,Result};
use std::{fs,io::Write,path::Path};
use zeroize::Zeroize;

fn load_seed(path:&Path)->Result<[u8;32]>{
    if let Some(dir)=path.parent(){fs::create_dir_all(dir)?;}
    match fs::read(path){
        Ok(bytes)=>bytes.try_into().map_err(|_|anyhow::anyhow!("Invalid private identity file")),
        Err(error) if error.kind()==std::io::ErrorKind::NotFound=>{
            let seed=iroh::SecretKey::generate().to_bytes();
            let mut options=fs::OpenOptions::new();options.write(true).create_new(true);
            #[cfg(unix)]{use std::os::unix::fs::OpenOptionsExt;options.mode(0o600);}
            let mut file=options.open(path)?;file.write_all(&seed)?;file.sync_all()?;Ok(seed)
        }
        Err(e)=>Err(e.into()),
    }
}
#[tokio::main]
async fn main()->Result<()>{
    let args:Vec<String>=std::env::args().skip(1).collect();
    match args.first().map(String::as_str){
        Some("serve") if args.len()==3||args.len()==4=>{
            let mut seed=load_seed(Path::new(&args[1]))?;
            let result=roots_whirlwind::serve(&seed,args[2].clone().into(),args.get(3).map(String::as_str)).await;
            seed.zeroize();result
        }
        Some("external-dht") if args.len()>=2=>{
            let mut seed=load_seed(Path::new(&args[1]))?;
            let addresses=args[2..].iter().map(|s|s.parse()).collect::<Result<Vec<libp2p::Multiaddr>,_>>()?;
            let result=roots_whirlwind::external_dht(&seed,addresses).await;seed.zeroize();result
        }
        _=>bail!("Usage: roots-whirlwind serve <internal-key-file> <allowed-devices-file> [relay-multiaddr] OR external-dht <separate-external-key-file> [bootstrap-multiaddr...]"),
    }
}
